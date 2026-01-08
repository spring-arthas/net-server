package com.alibaba.server.nio.service.file.task;

import com.alibaba.server.nio.core.server.BasicServer;
import com.alibaba.server.nio.handler.pipe.standard.DefaultChannelPipeLine;
import com.alibaba.server.nio.model.SocketChannelContext;
import com.alibaba.server.util.LocalTime;
import lombok.extern.slf4j.Slf4j;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 文件写任务处理
 * 
 * @author spring
 */
@Slf4j
@SuppressWarnings("all")
public final class FileDownloadTask implements Runnable {
    private SocketChannelContext socketChannelContext;
    private Map<String, Object> map;

    public FileDownloadTask(Map<String, Object> map) {
        this.map = map;
        this.socketChannelContext = (SocketChannelContext) map.get("SOCKET_CHANNEL_CONTEXT");
    }

    @Override
    public void run() {
        try {
            // 1、尝试加锁
            while (!BasicServer.fileLock.tryLock()) {
                try {
                    TimeUnit.MILLISECONDS.sleep(1);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            // log.info("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ]
            // FileDownloadTask | --> success acquire lock, thread = {}",
            // Thread.currentThread().getName());

            // 2、数据处理
            this.handler();
        } catch (Exception e) {
            e.printStackTrace();
            // 结束执行单元
            log.error(
                    "[ " + LocalTime.formatDate(LocalDateTime.now())
                            + " ] FileDownloadTask | --> thread = {}, error = {}",
                    Thread.currentThread().getName(), e.getMessage());
        } finally {
            BasicServer.fileLock.unlock();

            // 删除已处理的帧
            this.socketChannelContext.getRealList().clear();
            // log.info("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ]
            // FileDownloadTask | --> success release lock, thread = {}",
            // Thread.currentThread().getName());
        }
    }

    private void handler() throws IOException {
        // ((DefaultChannelPipeLine)
        // this.socketChannelContext.getChannelPipeLine()).executeHandler(this.map);
    }

}
