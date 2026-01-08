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

import org.apache.commons.lang.exception.ExceptionUtils;

/**
 * 文件上传服务端 [ServerSocketChannel] 绑定于10087
 * 
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
            if (!serverSocketChannel.isOpen() || !serverSocketChannel.isRegistered()) {
                throw new RuntimeException("ServerSocketChannel is not open or registered");
            }

            while (true) {
                // 循环处理所有待处理的连接，避免多客户端同时连接时丢失
                SocketChannel socketChannel;
                int acceptedCount = 0;
                while ((socketChannel = serverSocketChannel.accept()) != null) {
                    this.registerFileSocketChannel(socketChannel);
                    acceptedCount++;
                }

                if (acceptedCount > 0) {
                    log.debug("本次唤醒共接受 {} 个客户端连接", acceptedCount);
                }

                // 所有连接都处理完毕后再 park，等待 Selector 唤醒
                LockSupport.park();
            }
        } catch (Exception e) {
            log.error(
                    "MainFileUploadAcceptor：文件上传服务端监听处理异常, error = {}",
                    ExceptionUtils.getStackTrace(e));
        }
    }

    /**
     * 处理文件SocketChannel连接
     * 
     * @param socketChannel
     */
    private void registerFileSocketChannel(SocketChannel socketChannel) throws IOException {
        // 1、设置通道链处理器 --> 文件消息解码器 --> 文件消息真实数据处理器
        SocketChannelContext socketChannelContext = this.createModel(socketChannel);
        // 设置为上传类型，用于 Handler 选择
        socketChannelContext.setHandlerType("UPLOAD");
        NioServerContext.EventRegister(socketChannel, this.selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE)
                .attach(socketChannelContext);
        log.info("文件上传客户端通道成功接入，并完成该 [{}] 连接地址的socketChannel注册selector成功, 远程客户端地址 = {}",
                socketChannelContext.getRemoteAddress());
    }
}
