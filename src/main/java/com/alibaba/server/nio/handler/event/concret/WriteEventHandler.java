package com.alibaba.server.nio.handler.event.concret;

import com.alibaba.server.nio.handler.event.AbstractEventHandler;
import com.alibaba.server.nio.model.ChannelEventModel;
import com.alibaba.server.nio.model.SocketChannelContext;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Optional;

/**
 * 写事件处理器
 * 
 * 核心功能：
 * 1. 从 pendingWriteBuffer 中取出数据写入 SocketChannel
 * 2. 写完后取消 OP_WRITE 事件，避免水平触发死循环
 * 3. 支持文件上传 ACK 响应和文件下载数据发送
 * 
 * @author YSFY
 */
@Slf4j
@SuppressWarnings("all")
public class WriteEventHandler extends AbstractEventHandler {

    @Override
    public ChannelEventModel eventHandler(ChannelEventModel eventModel) {
        if (!super.checkEvent(eventModel)) {
            return eventModel;
        }

        if (!eventModel.getSelectionKey().isWritable()) {
            if (!Optional.ofNullable(super.getNextEventHandler()).isPresent()) {
                return eventModel;
            }
            return super.getNextEventHandler().eventHandler(eventModel);
        }

        return this.handler(eventModel);
    }

    /**
     * 执行写操作
     * 从 pendingWriteQueue 中取出数据写入 SocketChannel
     * 队列空时取消 OP_WRITE 事件，避免水平触发死循环
     */
    private ChannelEventModel handler(ChannelEventModel eventModel) {
        SelectionKey selectionKey = eventModel.getSelectionKey();
        SocketChannelContext socketChannelContext = (SocketChannelContext) selectionKey.attachment();

        if (socketChannelContext == null) {
            log.warn("WriteEventHandler: socketChannelContext 为空");
            WriteQueueHelper.cancelWriteInterest(selectionKey);
            return eventModel;
        }

        java.util.concurrent.ConcurrentLinkedQueue<ByteBuffer> queue = socketChannelContext.getPendingWriteQueue();

        // 同步块：防止与 WriteQueueHelper.submitWrite() 并发修改 ByteBuffer.position()
        synchronized (queue) {
            // 没有待写数据，取消 OP_WRITE
            if (queue.isEmpty()) {
                WriteQueueHelper.cancelWriteInterest(selectionKey);
                return eventModel;
            }

            SocketChannel socketChannel = (SocketChannel) selectionKey.channel();

            try {
                // 从队列中取出并写入数据
                ByteBuffer pendingBuffer;
                while ((pendingBuffer = queue.peek()) != null) {
                    int written = socketChannel.write(pendingBuffer);
                    log.debug("WriteEventHandler 写入: {} 字节, 剩余: {} 字节", written, pendingBuffer.remaining());

                    if (pendingBuffer.hasRemaining()) {
                        // 没写完，保持 OP_WRITE 注册，等下次写事件继续
                        break;
                    }
                    // 写完了，移除队列头
                    queue.poll();
                }

                // 队列空了，取消 OP_WRITE
                if (queue.isEmpty()) {
                    WriteQueueHelper.cancelWriteInterest(selectionKey);
                    log.debug("队列数据全部写完，已取消 OP_WRITE");
                }

            } catch (IOException e) {
                log.error("写入数据失败: remoteAddress={}", socketChannelContext.getRemoteAddress(), e);
                queue.clear();
                WriteQueueHelper.cancelWriteInterest(selectionKey);
            }
        }

        return eventModel;
    }

    /**
     * 追加待发送数据到通道附件（保持兼容性，用于老版本代码）
     * 
     * @param map 发送数据对象
     * @param o   当前通道的附件对象
     * @deprecated 建议使用 WriteQueueHelper.submitWrite() 代替
     */
    @Deprecated
    public static void addSendData(java.util.Map map, Object o) {
        SocketChannelContext socketChannelContext = (SocketChannelContext) o;
        /*
         * java.util.concurrent.LinkedBlockingQueue linkedBlockingQueue =
         * ((java.util.concurrent.LinkedBlockingQueue)
         * socketChannelContext.getBlockingQueue());
         * if (linkedBlockingQueue.remainingCapacity() > 0) {
         * linkedBlockingQueue.offer(map);
         * }
         */
    }
}
