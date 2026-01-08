package com.alibaba.server.nio.handler.event.concret;

import com.alibaba.server.common.BasicConstant;
import com.alibaba.server.nio.core.server.NioServerContext;
import com.alibaba.server.nio.model.SocketChannelContext;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

/**
 * 写操作助手类
 * 
 * 采用「先尝试直接写，写不完才注册 OP_WRITE」的策略：
 * 1. 先尝试直接写入 SocketChannel
 * 2. 如果一次写不完（TCP 缓冲区满），保存剩余数据到 pendingWriteBuffer
 * 3. 注册 OP_WRITE 事件，等 Selector 通知再继续写
 * 4. 写完后取消 OP_WRITE，避免水平触发死循环
 * 
 * 这种方式减少了内存占用，只有在写不完时才使用 pendingWriteBuffer
 * 
 * @author YSFY
 */
@Slf4j
public class WriteQueueHelper {

    /**
     * 写入数据（先尝试直接写，写不完才注册 OP_WRITE）
     * 
     * @param socketChannelContext 通道上下文
     * @param buffer               要写入的数据
     */
    public static void submitWrite(SocketChannelContext socketChannelContext, ByteBuffer buffer) {
        if (socketChannelContext == null || buffer == null || !buffer.hasRemaining()) {
            return;
        }

        try {
            SocketChannel socketChannel = socketChannelContext.getSocketChannel();
            if (socketChannel == null || !socketChannel.isOpen()) {
                log.warn("通道已关闭，无法写入");
                return;
            }

            // 1. 先尝试直接写入
            int written = socketChannel.write(buffer);
            log.debug("直接写入: {} 字节", written);

            // 2. 如果没写完，保存剩余数据并注册 OP_WRITE
            if (buffer.hasRemaining()) {
                // 复制剩余数据到 pendingWriteBuffer
                ByteBuffer pending = ByteBuffer.allocate(buffer.remaining());
                pending.put(buffer);
                pending.flip();
                socketChannelContext.setPendingWriteBuffer(pending);

                // 注册 OP_WRITE 事件
                registerWriteInterest(socketChannelContext);
                log.debug("数据未完全写入，剩余 {} 字节，已注册 OP_WRITE", pending.remaining());
            }

        } catch (IOException e) {
            log.error("写入数据失败: remoteAddress={}", socketChannelContext.getRemoteAddress(), e);
        }
    }

    /**
     * 注册 OP_WRITE 事件
     */
    private static void registerWriteInterest(SocketChannelContext socketChannelContext) {
        try {
            SocketChannel socketChannel = socketChannelContext.getSocketChannel();
            Selector selector = NioServerContext.getSelector(BasicConstant.NIO_SERVER_MAIN_CORE_FILE_SELECTOR);
            SelectionKey key = socketChannel.keyFor(selector);
            if (key != null && key.isValid()) {
                key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                selector.wakeup();
            }
        } catch (Exception e) {
            log.error("注册 OP_WRITE 失败", e);
        }
    }

    /**
     * 取消 OP_WRITE 事件（写完后调用，避免水平触发死循环）
     */
    public static void cancelWriteInterest(SelectionKey selectionKey) {
        if (selectionKey != null && selectionKey.isValid()) {
            int ops = selectionKey.interestOps();
            if ((ops & SelectionKey.OP_WRITE) != 0) {
                selectionKey.interestOps(ops & ~SelectionKey.OP_WRITE);
                log.debug("已取消 OP_WRITE 事件");
            }
        }
    }
}
