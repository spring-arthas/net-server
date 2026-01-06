package com.alibaba.server.nio.handler.event.concret;

import com.alibaba.server.common.BasicConstant;
import com.alibaba.server.common.FrameReadResultEnum;
import com.alibaba.server.nio.core.server.NioServerContext;
import com.alibaba.server.nio.handler.event.AbstractEventHandler;
import com.alibaba.server.nio.handler.worker.WorkerThreadPool;
import com.alibaba.server.nio.model.ChannelEventDataCacheModel;
import com.alibaba.server.nio.model.ChannelEventModel;
import com.alibaba.server.nio.model.ChannelEventModel.GroupData;
import com.alibaba.server.nio.model.SocketChannelContext;
import com.alibaba.server.nio.model.TransportDataModel;
import com.alibaba.server.nio.model.constant.ChannelEventModelEnum;
import com.alibaba.server.nio.reactor.GlobalMainReactor;
import com.alibaba.server.nio.reactor.SubReactor;
import com.alibaba.server.util.LocalTime;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;
import java.time.LocalDateTime;
import java.util.*;

/**
 * @Auther: YSFY
 * @Date: 2020-11-21 12:07
 * @Pacage_name: com.alibaba.server.nio.Handler.concret
 * @Project_Name: net-server
 * @Description: 读事件处理[ReadEventHandler]
 */
@Slf4j
@SuppressWarnings("all")
public class ReadEventHandler extends AbstractEventHandler {
    private static Integer chatIndex = 0;

    /**
     * 帧总长度最大限制：100MB
     * 防止恶意客户端发送超大帧长度导致OOM
     */
    private static final int MAX_FRAME_LENGTH = 100 * 1024 * 1024;

    @Override
    public ChannelEventModel eventHandler(ChannelEventModel channelEventModel) {
        // 非读事件则继续下一个事件处理器
        if (!super.checkEvent(channelEventModel)) {
            return channelEventModel;
        }
        if (!channelEventModel.getSelectionKey().isReadable()) {
            if (!Optional.ofNullable(super.getNextEventHandler()).isPresent()) {
                return channelEventModel;
            }
            return super.getNextEventHandler().eventHandler(channelEventModel);
        }

        // 读事件处理
        this.readHandler(channelEventModel);
        return channelEventModel;
    }

    /**
     * 执行处理,提交线程池处理读事件,解决粘包半包问题后，提交数据至线程池处理
     * 
     * @param channelEventModel
     * @return eventModel
     */
    private void readHandler(ChannelEventModel channelEventModel) {
        // 1. 优先在服务端为本次读事件对应的通道构建数据缓存模型，后续只要是是这个socketChannel读事件需要处理
        // 优先使用该数据缓存对象
        ChannelEventDataCacheModel channelEventDataCacheModel = this.createChannelEventModelCache(channelEventModel);
        // 2. 读取当前通道读事件内所有待处理字节数据，即本次读事件唤醒能从socket中读取到多少数据通过该readData一次
        // 性读取完毕，并返回读取结果
        FrameReadResultEnum frameReadResultEnum = this.readData(channelEventModel, channelEventDataCacheModel);
        if (Objects.equals(FrameReadResultEnum.END, frameReadResultEnum)) { // 通道关闭直接返回，内部已经完成了资源的释放
            return;
        }
        // 3. 按需处理已经完成读取的字节数据
        // 3.1 如果当前读事件对应的通道为文本处理通道，则按照文本方式处理
        if (Objects.equals(ChannelEventModelEnum.TEXT_TRANSMISSION, channelEventModel.getEventModelEnum())) {
            this.doTextDataHandle(channelEventModel);
            return;
        }
        // 3.2、如果当前读事件对应的通道为文件处理通道，则按照文件方式处理
        if (Objects.equals(ChannelEventModelEnum.FILE_UPLOAD, channelEventModel.getEventModelEnum())
                || Objects.equals(ChannelEventModelEnum.FILE_DOWNLOAD, channelEventModel.getEventModelEnum())) {
            // 将本次待处理的数据缓存至socketChannelContext中
            SocketChannelContext socketChannelContext = (SocketChannelContext) channelEventModel.getSelectionKey()
                    .attachment();
            TransportDataModel transportDataModel = new TransportDataModel();
            transportDataModel.setDataType(channelEventModel.getEventModelEnum().getName());
            transportDataModel.setWaitHandleDataList(channelEventDataCacheModel.getWaitHandleDataList());
            socketChannelContext.setTransportDataModel(transportDataModel);
            // 将本次读事件产生的数据传递至事件对应通道的线程内进行处理，注意当前线程依旧是MainFileSelector线程
            WorkerThreadPool.submit(socketChannelContext);
        }

        // 4. 释放缓存数据:【修复】：清空 completeList，防止数据重复处理
        channelEventDataCacheModel.getWaitHandleDataList().clear();

    }

    /**
     * 读取数据
     * 
     * @param channelEventModel     本通道本次产生的可选事件对应的元数据
     * @param channelCacheDataModel 当前通道缓存数据
     * @return
     */
    private FrameReadResultEnum readData(ChannelEventModel channelEventModel,
            ChannelEventDataCacheModel channelCacheDataModel) {
        FrameReadResultEnum frameReadResultEnum = FrameReadResultEnum.END;
        // 1. 获取当前读事件对应的socketChannel通道
        SocketChannel socketChannel = (SocketChannel) channelEventModel.getSelectionKey().channel();
        // 2. 获取本通道对应的SocketChannelContext上下文，实时记录的是该通道的上下文数据
        SocketChannelContext socketChannelContext = (SocketChannelContext) channelEventModel.getSelectionKey()
                .attachment();
        // 3. 从套接字上读取数据
        ByteBuffer byteBuffer = socketChannelContext.getByteBuffer();
        try {
            // 按照byteBuffer大小从通道读取数据，只要有数据就一直进行读取并写入byteBuffer
            frameReadResultEnum = this.doReadDataHandle(channelEventModel, channelCacheDataModel, socketChannel,
                    byteBuffer);
            // 客户端关闭输入输出流或直接调用close()会读取到-1
            if (Objects.equals(FrameReadResultEnum.END, frameReadResultEnum)) {
                // 1. 记录日志：客户端关闭连接
                log.info("[ " + LocalTime.formatDate(LocalDateTime.now())
                        + " ] ReadEventHandler | --> 客户端关闭连接，准备释放资源, remoteAddress = {}, thread = {}",
                        channelEventModel.getRemoteAddress(), Thread.currentThread().getName());
                // 2. 取消 SelectionKey，从 Selector 中移除该通道的事件监听
                channelEventModel.getSelectionKey().cancel();
                // 3. 关闭通道并释放资源
                // closedAndRelease 内部会执行：
                // - 关闭 SocketChannel（shutdownInput/shutdownOutput/close）
                // - 调用 handleSubReactor 清理：channelDataMap、用户缓存、数据库状态更新、SubReactor 线程
                NioServerContext.closedAndRelease(socketChannel);
                // 4. 处理完成，直接返回
                return FrameReadResultEnum.END;
            }

            // 需要后续处理读取到的数据
            if (Objects.equals(FrameReadResultEnum.NEED_HANDLE, frameReadResultEnum)) {
                return FrameReadResultEnum.NEED_HANDLE;
            }
        } catch (Exception e) {
            // 处理 read 产生的异常
            log.error("[ " + LocalTime.formatDate(LocalDateTime.now())
                    + " ] ReadEventHandler | --> 读取数据异常，准备释放资源, remoteAddress = {}, exception = {}, thread = {}",
                    channelEventModel.getRemoteAddress(), e.getClass().getSimpleName(),
                    Thread.currentThread().getName());

            if (e instanceof SocketException) {
                // Connection reset || Connection reset by peer:Socket write error || Broken
                // pipe
                log.warn("SocketException: {}", e.getMessage());
            } else if (e instanceof ClosedChannelException) {
                // socketChannel 已经关闭依旧发生 read
                log.warn("ClosedChannelException: 通道已关闭, message = {}", e.getMessage());
            }
            // 取消 SelectionKey
            channelEventModel.getSelectionKey().cancel();
            // 关闭通道并释放资源（内部会清理 channelDataMap、用户缓存等）
            NioServerContext.closedAndRelease(socketChannel);
            return FrameReadResultEnum.END;
        } finally {
            byteBuffer.clear();
        }
        return frameReadResultEnum;
    }

    /**
     * 从套接字上读取数据
     * 
     * 循环读取逻辑：
     * 1. readBytes > 0：成功读取到数据，继续循环读取
     * 2. readBytes == 0：暂时没有数据可读（非阻塞模式），返回 NEED_HANDLE 等待下次事件
     * 3. readBytes == -1：流末尾（客户端关闭连接），返回 END 触发通道关闭
     * 
     * @param channelEventModel
     * @param channelCacheDataModel
     * @param socketChannel
     * @param byteBuffer
     * @return FrameReadResultEnum.END 表示流末尾需关闭通道，NEED_HANDLE 表示需要后续处理
     * @throws IOException
     */
    private FrameReadResultEnum doReadDataHandle(ChannelEventModel channelEventModel,
            ChannelEventDataCacheModel channelCacheDataModel,
            SocketChannel socketChannel, ByteBuffer byteBuffer) throws IOException {
        int readBytes;
        // 循环从通道读取数据
        while ((readBytes = socketChannel.read(byteBuffer)) > 0) {
            // 从通道读取完并写入到byteBuffer后切换为读模式，将byteBuffer数据重新设置到bytes数组中
            byteBuffer.flip();
            if (byteBuffer.hasRemaining()) {
                // 读取原始数据
                byte[] bytes = new byte[byteBuffer.remaining()];
                byteBuffer.get(bytes);
                // 封装当前数据
                GroupData groupData = channelEventModel.new GroupData();
                groupData.setLength(bytes.length);
                groupData.setIndex(channelCacheDataModel.getNewLatestIndex());
                groupData.setBytes(bytes);
                groupData.setStatus("UN_HANDLE");
                // 处理当前通道对应缓存数据模型内的数据序号【修复】：索引溢出保护，防止long连接场景下index溢出变为负数
                channelCacheDataModel
                        .setNewLatestIndex(channelCacheDataModel.getNewLatestIndex() >= (Integer.MAX_VALUE - 1) ? 1
                                : groupData.getIndex() + 1);
                channelCacheDataModel.getWaitHandleDataList().add(groupData);
                byteBuffer.clear();
            }
        }

        // 判断读取结果：-1 表示流末尾（客户端关闭连接），需要关闭通道
        if (readBytes == -1) {
            return FrameReadResultEnum.END;
        }

        // readBytes == 0 或正常读取完成，返回需要后续处理
        return FrameReadResultEnum.NEED_HANDLE;
    }

    /**
     * 文字传输处理
     * 
     * @param channelEventModel
     */
    private void doTextDataHandle(ChannelEventModel channelEventModel) {
        channelEventModel.setIndex((chatIndex > Integer.MAX_VALUE) ? 0 : chatIndex++);

        // 2.1、获取当前通道对应的subReactor线程
        SubReactor reactor = GlobalMainReactor
                .getSubReactorForSocketChannel((SocketChannel) channelEventModel.getSelectionKey().channel());
        if (!Optional.ofNullable(reactor).isPresent()) {
            // 为空，表示没有找到当前通道对应的Subreacto线程，有可能为之前用户下线导致清除了用户对应的SubReactor线程，但是socketChannel并未断开，故此时重新建立新的Subreactor即可
            this.registerSubReactor(channelEventModel, BasicConstant.NIO_SERVER_MAIN_CORE_CHAT_SELECTOR);
            return;
        }

        // 2.2、将当前通道触发的读事件数据 --> 加入当前SocketChannel连接对应的的Subreactor线程数据处理队列, 可用空间必须大于0
        if (reactor.getQueue().remainingCapacity() > 0) {
            // Map<String, Object> queueMap = new HashMap<>();
            // queueMap.put("SOCKET_CHANNEL_CONTEXT",
            // channelEventModel.getSelectionKey().attachment());
            // queueMap.put("COMPLETE_LIST", channelEventModel.getWaitHandleDataList());
            // reactor.getQueue().offer(queueMap);
            //
            // // 【修复】：清空 completeList，防止数据重复处理
            // // completeList 已经传递给下游，必须清空，否则下次读事件会携带旧数据
            // channelEventModel.getWaitHandleDataList().clear();
            return;
        }

        log.warn("[ " + LocalTime.formatDate(LocalDateTime.now())
                + " ] ReadEventHandler | --> 聊天服务通道 [{}] 对应的SubReactor线程数据处理队列已满, 队列可用空间 = [{}], address = {}, thread = {}",
                ((SocketChannelContext) channelEventModel.getSelectionKey().attachment()).getRemoteAddress(),
                reactor.getQueue().remainingCapacity(), Thread.currentThread().getName());

        // 【修复】：队列满时不丢弃数据，保持 completeList 不清空，等待下次重试
        // 但需要注意：如果队列长时间满载，会导致内存累积
    }
}
