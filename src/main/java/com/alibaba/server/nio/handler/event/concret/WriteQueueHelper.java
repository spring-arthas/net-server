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
 * 采用「先尝试直接写，写不完加入队列」的策略：
 * 1. 先尝试写入队列中已有的待写数据
 * 2. 再尝试直接写入新数据到 SocketChannel
 * 3. 如果一次写不完（TCP 缓冲区满），加入队列并注册 OP_WRITE
 * 4. 写完后取消 OP_WRITE，避免水平触发死循环
 * 
 * 使用队列可以避免快速连续调用时数据丢失
 * 
 * @author YSFY
 */
@Slf4j
public class WriteQueueHelper {

    /**
     * 写入数据（先尝试直接写，写不完则加入队列）
     * 
     * @param socketChannelContext 通道上下文
     * @param buffer               要写入的数据
     */
    public static void submitWrite(SocketChannelContext socketChannelContext, ByteBuffer buffer) throws IOException {
        if (socketChannelContext == null || buffer == null || !buffer.hasRemaining()) {
            return;
        }

        try {
            SocketChannel socketChannel = socketChannelContext.getSocketChannel();
            if (socketChannel == null || !socketChannel.isOpen()) {
                log.warn("通道已关闭，无法写入，清理队列并关闭通道");
                // 清理队列避免无限循环
                socketChannelContext.getPendingWriteQueue().clear();
                // 尝试关闭通道
                if (socketChannel != null) {
                    try {
                        socketChannel.close();
                    } catch (Exception ignored) {
                    }
                }
                return;
            }

            java.util.concurrent.ConcurrentLinkedQueue<ByteBuffer> queue = socketChannelContext.getPendingWriteQueue();

            // 同步块：防止与 WriteEventHandler 并发修改 ByteBuffer.position()
            synchronized (queue) {
                // 1. 先处理队列中已有的待写数据
                ByteBuffer pending;
                while ((pending = queue.peek()) != null) {
                    int written = socketChannel.write(pending);
                    if (pending.hasRemaining()) {
                        // 队列头的数据没写完，新数据入队尾
                        ByteBuffer copy = ByteBuffer.allocate(buffer.remaining());
                        copy.put(buffer);
                        copy.flip();
                        queue.offer(copy);
                        registerWriteInterest(socketChannelContext);
                        log.debug("队列数据未完全写入，重新注册写事件，新数据入队，队列大小: {}", queue.size());
                        return;
                    }
                    // 写完了，移除队列头
                    queue.poll();
                }

                // 2. 队列空了，尝试直接写入新数据
                int written = socketChannel.write(buffer);
                log.debug("直接写入: {} 字节", written);

                // 3. 如果没写完，加入队列并注册 OP_WRITE
                if (buffer.hasRemaining()) {
                    ByteBuffer copy = ByteBuffer.allocate(buffer.remaining());
                    copy.put(buffer);
                    copy.flip();
                    queue.offer(copy);
                    registerWriteInterest(socketChannelContext);
                    log.debug("数据未完全写入，剩余 {} 字节加入队列", copy.remaining());
                }
            }

        } catch (IOException e) {
            log.error("写入数据失败: remoteAddress={}", socketChannelContext.getRemoteAddress(), e);
            throw e;
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

    /**
     * 等待队列完全清空（所有数据发送完成）
     * 用于在关闭连接前确保所有数据已发送
     * 
     * 特点：
     * - 无固定超时，持续等待直到队列清空或通道关闭
     * - 活动检测：如果队列大小持续减少，说明数据正在发送，继续等待
     * - 卡死检测：如果队列大小长时间不变（30秒），认为连接卡死
     * 
     * @param socketChannelContext 通道上下文
     * @param stuckTimeoutMs       卡死超时时间（毫秒），队列大小不变超过此时间认为卡死，0表示不检测
     * @return true 如果队列已清空，false 如果通道关闭或卡死
     */
    public static boolean waitForQueueDrain(SocketChannelContext socketChannelContext, long stuckTimeoutMs) {
        if (socketChannelContext == null) {
            return true;
        }

        java.util.concurrent.ConcurrentLinkedQueue<java.nio.ByteBuffer> queue = socketChannelContext
                .getPendingWriteQueue();
        if (queue.isEmpty()) {
            return true;
        }

        log.info("等待队列清空，当前队列大小: {}", queue.size());

        long startTime = System.currentTimeMillis();
        long lastActivityTime = startTime;
        int lastQueueSize = queue.size();
        int pollIntervalMs = 50; // 每50ms检查一次
        long lastLogTime = startTime;

        while (!queue.isEmpty()) {
            int currentQueueSize = queue.size();

            // 检测活动：队列大小变化说明有数据正在发送
            if (currentQueueSize != lastQueueSize) {
                lastActivityTime = System.currentTimeMillis();
                lastQueueSize = currentQueueSize;
            }

            // 卡死检测：队列大小长时间不变
            if (stuckTimeoutMs > 0) {
                long noActivityDuration = System.currentTimeMillis() - lastActivityTime;
                if (noActivityDuration > stuckTimeoutMs) {
                    log.warn("队列发送卡死，{}ms 内队列大小无变化，剩余: {} 个缓冲区", stuckTimeoutMs, currentQueueSize);
                    return false;
                }
            }

            // 检查通道是否仍然打开
            SocketChannel socketChannel = socketChannelContext.getSocketChannel();
            if (socketChannel == null || !socketChannel.isOpen()) {
                log.warn("通道已关闭，停止等待，剩余未发送数据: {} 个缓冲区", currentQueueSize);
                return false;
            }

            // 定期记录进度（每5秒）
            long now = System.currentTimeMillis();
            if (now - lastLogTime >= 5000) {
                long elapsed = now - startTime;
                log.info("等待队列清空中... 已等待 {}ms，剩余: {} 个缓冲区", elapsed, currentQueueSize);
                lastLogTime = now;
            }

            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("等待队列清空被中断");
                return false;
            }
        }

        long totalTime = System.currentTimeMillis() - startTime;
        log.info("队列已清空，总耗时: {}ms", totalTime);
        return true;
    }
}
