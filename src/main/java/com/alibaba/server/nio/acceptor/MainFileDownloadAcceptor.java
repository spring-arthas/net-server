package com.alibaba.server.nio.acceptor;

import com.alibaba.server.common.BasicConstant;
import com.alibaba.server.nio.core.server.NioServerContext;
import com.alibaba.server.nio.model.SocketChannelContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.exception.ExceptionUtils;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.locks.LockSupport;

/**
 * 文件下载服务端线程 [ServerSocketChannel] 绑定于10088
 * 
 * @author: YSFY
 * @Date: 2020-11-21 10:40
 * @Pacage_name: com.alibaba.server.nio.selector
 * @Project_Name: net-server
 * @Description: 文件 Selector
 */
@Slf4j
@SuppressWarnings("all")
public class MainFileDownloadAcceptor extends AbstractAcceptor implements Runnable {
    private String acceptor = "";
    private Selector selector = null;

    public MainFileDownloadAcceptor(String acceptor) {
        this.acceptor = acceptor;
        this.selector = NioServerContext.getSelector(BasicConstant.NIO_SERVER_MAIN_CORE_FILE_SELECTOR);
    }

    @Override
    public void run() {
        try {
            ServerSocketChannel serverSocketChannel = super.initServerSocketChannel(selector, this.acceptor);

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
                    log.debug("本次唤醒共接受 {} 个客户端下载连接", acceptedCount);
                }

                // 所有连接都处理完毕后再 park，等待 Selector 唤醒
                LockSupport.park();
            }
        } catch (Exception e) {
            log.error(
                    "MainFileDownloadAcceptor：文件下载服务端监听处理异常, error = {}",
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
        // 设置为下载类型，用于 Handler 选择
        socketChannelContext.setHandlerType("DOWNLOAD");
        NioServerContext.EventRegister(socketChannel, this.selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE)
                .attach(socketChannelContext);
        log.info("文件下载客户端通道成功接入，并完成该 [{}] 连接地址的socketChannel注册selector成功, 远程客户端地址 = {}",
                socketChannelContext.getRemoteAddress());
    }
}
