package com.alibaba.server.nio.service.chat.task;

import com.alibaba.server.nio.core.server.BasicServer;
import com.alibaba.server.nio.handler.pipe.standard.DefaultChannelPipeLine;
import com.alibaba.server.nio.model.SocketChannelContext;
import com.alibaba.server.util.LocalTime;
import lombok.extern.slf4j.Slf4j;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 聊天消息执行读取处理单元
 *
 * @author spring
 * */

@Slf4j
@SuppressWarnings("all")
public final class ChatReadEventTask implements Runnable {
    private Map<String, Object> map;

    public ChatReadEventTask(Map<String, Object> map) {
        this.map = map;
    }

    @Override
    public void run() {
        try {
            // 1、尝试加锁
            while (!BasicServer.chatLock.tryLock()) {
                try {
                    TimeUnit.MILLISECONDS.sleep(1);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            log.info("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] ChatReadEventTask | --> success acquire lock, thread = {}", Thread.currentThread().getName());

            // 2、封装数据
            this.handler();
        } catch (Exception e) {
            // 结束执行单元
            log.error("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] ChatReadEventTask | --> thread = {}, error = {}",
                Thread.currentThread().getName() , e.getMessage());
        } finally {
            BasicServer.chatLock.unlock();
            // 3、释放锁
            log.info("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] ChatReadEventTask | --> success release lock, thread = {}", Thread.currentThread().getName());
        }
    }

    private void handler() throws IOException {
        // 1、获取当前通道元数据上下文
        SocketChannelContext socketChannelContext = (SocketChannelContext) this.map.get("SOCKET_CHANNEL_CONTEXT");

        // 2、执行管道处理
        ((DefaultChannelPipeLine) socketChannelContext.getChannelPipeLine()).executeHandler(this.map);
    }
}
