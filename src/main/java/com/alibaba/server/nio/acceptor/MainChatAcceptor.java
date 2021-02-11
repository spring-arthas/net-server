package com.alibaba.server.nio.acceptor;

import com.alibaba.server.common.BasicConstant;
import com.alibaba.server.nio.core.server.NioServerContext;
import com.alibaba.server.nio.reactor.GlobalMainReactor;
import com.alibaba.server.util.LocalTime;
import lombok.extern.slf4j.Slf4j;

import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.locks.LockSupport;

/**
 * @Auther: YSFY
 * @Date: 2020-11-21 10:38
 * @Pacage_name: com.alibaba.server.nio.selector
 * @Project_Name: net-server
 * @Description: 聊天Acceptor
 */

@Slf4j
@SuppressWarnings("all")
public class MainChatAcceptor extends AbstractAcceptor implements Runnable {
    private String acceptor = "";
    private Selector selector = null;

    public MainChatAcceptor(String acceptor) {
        this.acceptor = acceptor;
        this.selector = NioServerContext.getSelector(BasicConstant.NIO_SERVER_MAIN_CORE_CHAT_SELECTOR);
    }

    @Override
    public void run() {
        try {
            ServerSocketChannel serverSocketChannel = super.initServerSocketChannel(selector, this.acceptor);

            if(!serverSocketChannel.isOpen() || ! serverSocketChannel.isRegistered()) {
                throw new RuntimeException("ServerSocketChannel is not open or registered");
            }

            while (true) {
                SocketChannel socketChannel = serverSocketChannel.accept();
                if(Optional.ofNullable(socketChannel).isPresent()) {
                    GlobalMainReactor.registerStart(this.selector, socketChannel, this.acceptor);
                }

                LockSupport.park();
            }
        } catch (Exception e) {
            log.error("[" + LocalTime.formatDate(LocalDateTime.now()) + "] MainChatAcceptor | --> chat服务端监听处理异常, error = {}", e.getMessage());
        }
    }
}
