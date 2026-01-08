package com.alibaba.server.nio.service.file.task;

import com.alibaba.fastjson.JSON;
import com.alibaba.server.nio.core.server.BasicServer;
import com.alibaba.server.nio.core.server.NioServerContext;
import com.alibaba.server.nio.handler.pipe.standard.DefaultChannelPipeLine;
import com.alibaba.server.nio.model.SocketChannelContext;
import com.alibaba.server.nio.model.TransportDataModel;
import com.alibaba.server.util.LocalTime;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.exception.ExceptionUtils;

import java.nio.channels.SocketChannel;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 文件消息执行读取处理单元 --> 由获取对应的subReactor线程的队列中的数据并封装为当前task开启执行
 * 
 * @author spring
 */
@Slf4j
@SuppressWarnings("all")
public final class FileUploadTask implements Runnable {
    private SocketChannelContext socketChannelContext;

    public FileUploadTask(SocketChannelContext sockethannelContext) {
        this.socketChannelContext = socketChannelContext;
    }

    @Override
    public void run() {
        try {
            // 1、尝试加锁
            while (!BasicServer.fileLock.tryLock()) {
                try {
                    TimeUnit.MILLISECONDS.sleep(500);
                } catch (InterruptedException e) {
                    continue;
                }
            }

            // 2、封装数据并执行处理管道
            DefaultChannelPipeLine defaultChannelPipeLine = (DefaultChannelPipeLine) this.socketChannelContext
                    .getChannelPipeLine();
            defaultChannelPipeLine.executeHandler(this.socketChannelContext);
        } catch (Exception e) {
            log.error(
                    "当前线程{}执行文件上传任务异常, 将执行资源释放操作, socketChannelContext = {}, error = {}",
                    Thread.currentThread().getName(),
                    JSON.toJSONString(this.socketChannelContext),
                    ExceptionUtils.getStackTrace(e));
            // 异常时关闭通道并释放资源
            this.closeChannelOnError();
        } finally {
            // 3、释放锁
            BasicServer.fileLock.unlock();

            // 删除已处理的帧,如果不清空，会导致数据重复处理
            this.socketChannelContext.getRealList().clear();
        }
    }

    /**
     * 异常时关闭通道并释放资源
     * 由于提交任务的线程和执行任务的线程不是同一个线程，
     * 需要在任务内部捕获异常时主动关闭通道，防止资源泄漏
     */
    private void closeChannelOnError() {
        try {
            // todo 后续将SocketChannel移动至SocketChannelContext中
            SocketChannel socketChannel = this.socketChannelContext.getSocketChannel();
            if (socketChannel != null && socketChannel.isOpen()) {
                // closedAndRelease 内部会：
                // 1. 关闭 SocketChannel（shutdownInput/shutdownOutput/close）
                // 2. 调用 handleSubReactor 清理：channelDataMap、用户缓存、数据库状态更新、SubReactor 线程
                NioServerContext.closedAndRelease(socketChannel);
                log.info("[ " + LocalTime.formatDate(LocalDateTime.now())
                        + " ] FileUploadTask | --> 异常发生，已关闭通道, remoteAddress = {}, thread = {}",
                        this.socketChannelContext.getRemoteAddress(), Thread.currentThread().getName());
            }
        } catch (Exception ex) {
            log.error("[ " + LocalTime.formatDate(LocalDateTime.now())
                    + " ] FileUploadTask | --> 关闭通道异常: {}", ex.getMessage());
        }
    }
}
