package com.alibaba.server.nio.acceptor;

import com.alibaba.server.common.BasicConstant;
import com.alibaba.server.nio.core.server.NioServerContext;
import com.alibaba.server.nio.handler.pipe.standard.DefaultChannelPipeLine;
import com.alibaba.server.nio.handler.pipe.standard.SimpleChannelContext;
import com.alibaba.server.nio.model.SocketChannelContext;
import com.alibaba.server.nio.model.TransportProtocol;
import com.alibaba.server.nio.service.file.handler.*;
import com.alibaba.server.util.LocalTime;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.LockSupport;

/**
 * @author: YSFY
 * @Date: 2020-11-21 10:40
 * @Pacage_name: com.alibaba.server.nio.selector
 * @Project_Name: net-server
 * @Description: 文件 Selector
 */
@Slf4j
@SuppressWarnings("all")
public class MainFileUploadAcceptor extends AbstractAcceptor implements Runnable {
    private String ACCEPTOR = "";
    private Selector selector = null;

    public MainFileUploadAcceptor(String acceptor) {
        this.ACCEPTOR = acceptor;
        this.selector = NioServerContext.getSelector(BasicConstant.NIO_SERVER_MAIN_CORE_FILE_SELECTOR);
    }

    @Override
    public void run() {
        try {
            ServerSocketChannel serverSocketChannel = super.initServerSocketChannel(selector, this.ACCEPTOR);

            if(!serverSocketChannel.isOpen() || ! serverSocketChannel.isRegistered()) {
                throw new RuntimeException("ServerSocketChannel is not open or registered");
            }

            while (true) {
                SocketChannel socketChannel = serverSocketChannel.accept();
                if(Optional.ofNullable(socketChannel).isPresent()) {
                    // 处理文件SocketChannel连接
                    this.registerFileSocketChannel(socketChannel);
                }

                LockSupport.park();
            }
        } catch (Exception e) {
            log.error("[" + LocalTime.formatDate(LocalDateTime.now()) + "] MainFileUploadAcceptor | --> 文件上传服务端监听处理异常, error = {}", e.getMessage());
        }
    }

    /**
     * 处理文件SocketChannel连接
     * @param socketChannel
     */
    private void registerFileSocketChannel(SocketChannel socketChannel) throws IOException {
        // 1、设置通道链处理器 --> 文件消息解码器 --> 文件消息真实数据处理器
        SocketChannelContext socketChannelContext = this.createModel(socketChannel);
        NioServerContext.EventRegister(socketChannel, this.selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE).attach(socketChannelContext);
        log.info("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] MainFileUploadAcceptor | --> " + "上传通道注册成功, registerType = {}, remoteAddress = {}, registered = {}",
            BasicConstant.REGISTER_TYPE_FILE, socketChannelContext.getRemoteAddress(), socketChannel.isRegistered());
    }
}
