package com.alibaba.server.nio.acceptor;

import com.alibaba.server.common.BasicConstant;
import com.alibaba.server.nio.core.server.NioServerContext;
import com.alibaba.server.nio.handler.pipe.standard.DefaultChannelPipeLine;
import com.alibaba.server.nio.handler.pipe.standard.SimpleChannelContext;
import com.alibaba.server.nio.model.SocketChannelContext;
import com.alibaba.server.nio.service.file.handler.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Optional;

/**
 * @Auther: YSFY
 * @Date: 2020-11-21 11:30
 * @Pacage_name: com.alibaba.server.nio.selector
 * @Project_Name: net-server
 * @Description: 公共Selector处理类
 */

@Slf4j
@SuppressWarnings("all")
public class AbstractAcceptor {

    public Selector getCheck(String selectorName) {
        Selector selector = NioServerContext.getSelector(selectorName);
        if (!Optional.ofNullable(selector).isPresent()) {
            throw new RuntimeException("can not get [ " + selectorName + " ] selector from cache");
        }

        if (!selector.isOpen()) {
            throw new RuntimeException("[ " + selectorName + " ] selector is not open");
        }

        return selector;
    }

    /**
     * 创建不同的ServerSocketChannel
     * 
     * @param selector
     * @param assign
     * @return serverSocketChannel
     * @throws IOException
     */
    protected ServerSocketChannel initServerSocketChannel(Selector selector, String assign) throws IOException {
        InetSocketAddress inetSocketAddress = this.create(assign);
        ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();

        serverSocketChannel.bind(inetSocketAddress);
        serverSocketChannel.configureBlocking(false);
        selector.wakeup();
        serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);

        log.info(
                "[" + Thread.currentThread().getName() + " ] AbstractAcceptor | --> [ " + assign
                        + " ] 服务端通道构建成功, 正在等待客户端连接, 监听地址为 = {}, 本监听器对应线程名称 = {}",
                inetSocketAddress.toString(), Thread.currentThread().getName());
        return serverSocketChannel;
    }

    /**
     * 创建不同的InetSocketAddress
     * 
     * @param assign
     * @return serverSocketChannel
     * @throws UnknownHostException
     */
    private InetSocketAddress create(String assign) throws UnknownHostException {
        String ip = NioServerContext.getValue(BasicConstant.NIO_BIND_IP);
        if (StringUtils.isBlank(ip)) {
            ip = NioServerContext.getValue(BasicConstant.SERVER_IP);
        }
        if (StringUtils.isBlank(ip) || StringUtils.isEmpty(ip)) {
            throw new RuntimeException("ServerSocektChannle Listener Ip is empty or blank");
        }
        String port = "";
        if (StringUtils.equals(BasicConstant.NIO_SERVER_MAIN_CORE_TEXT_ACCEPTOR, assign)) { // 文本传输端口
            port = NioServerContext.getValue(BasicConstant.NIO_TEXT_PORT);
        }
        if (StringUtils.equals(BasicConstant.NIO_SERVER_MAIN_CORE_FILE_UPLOAD_ACCEPTOR, assign)) { // 文件上传端口
            port = NioServerContext.getValue(BasicConstant.NIO_FILE_UPLOAD_PORT);
        }
        if (StringUtils.equals(BasicConstant.NIO_SERVER_MAIN_CORE_FILE_DOWNLOAD_ACCEPTOR, assign)) { // 文件下载端口
            port = NioServerContext.getValue(BasicConstant.NIO_FILE_DOWNLOAD_PORT);
        }
        if (StringUtils.equals(BasicConstant.NIO_SERVER_MAIN_CORE_FILE_RESUME_UPLOAD_ACCEPTOR, assign)) { // 文件断点续传上传端口
            port = NioServerContext.getValue(BasicConstant.NIO_FILE_RESUME_UPLOAD_PORT);
        }
        if (StringUtils.equals(BasicConstant.NIO_SERVER_MAIN_CORE_FILE_RESUME_DOWNLOAD_ACCEPTOR, assign)) { // 文件断点续传下载端口
            port = NioServerContext.getValue(BasicConstant.NIO_FILE_RESUME_DOWNLOAD_PORT);
        }
        if (StringUtils.equals(BasicConstant.NIO_SERVER_MAIN_CORE_WEBSOCKET_ACCEPTOR, assign)) { // WebSocket端口
            port = NioServerContext.getValue(BasicConstant.NIO_WEBSOCKET_PORT);
        }
        if (StringUtils.isBlank(ip) || StringUtils.isEmpty(ip)) {
            throw new RuntimeException("ServerSocektChannle Listener port is empty or blank");
        }

        return new InetSocketAddress(InetAddress.getByName(ip), Integer.valueOf(port));
    }

    /**
     * 处理连接(配置socket参数，通道附件等)
     * 
     * @param socketChannel
     * @return
     * @throws IOException
     */
    public SocketChannelContext createModel(SocketChannel socketChannel) throws IOException {
        // 1、配置SocketChannel基本参数
        socketChannel.configureBlocking(false);
        // 是否启用TCP心跳机制 true：启用
        socketChannel.socket().setKeepAlive(true);
        // 是否一有数据就马上发送。 true：启用
        socketChannel.socket().setTcpNoDelay(false);
        // 优雅的关闭socket连接，并发送-1 (优雅地关闭套接字，或者立刻关闭)
        /**
         * 这个Socket选项可以影响close方法的行为。
         * 在默认情况下，当调用close方法后，将立即返回；
         * 1、如果这时仍然有未被送出的数据包，那么这些数据包将被丢弃。
         * 2、如果将linger参数设为一个正整数n时(n的值最大是65,535)，在调用close方法后，将最多被阻塞n秒。在这n秒内，系统将尽量将未送出的数据包发送出去；
         * 2.1、如果超过了n秒，如果还有未发送的数据包，这些数据包将全部被丢弃；而close方法会立即返回。
         * 3、如果将linger设为0，和关闭SO_LINGER选项的作用是一样的。
         */
        socketChannel.socket().setSoLinger(true, 20);
        // 对ServerSocket来说表示等待连接的最长空等待时间; 对Socket来说表示读数据最长空等待时间。
        socketChannel.socket().setSoTimeout(Integer.parseInt(NioServerContext.getValue(BasicConstant.SOCKET_TIMEOUT)));
        // 使用 StandardSocketOptions 设置更大的 TCP 缓冲区（优化大文件传输性能）
        try {
            socketChannel.setOption(java.net.StandardSocketOptions.SO_SNDBUF, 262144); // 256KB 发送缓冲区
            socketChannel.setOption(java.net.StandardSocketOptions.SO_RCVBUF, 262144); // 256KB 接收缓冲区
            log.debug("TCP缓冲区已优化: SO_SNDBUF={}KB, SO_RCVBUF={}KB",
                    socketChannel.getOption(java.net.StandardSocketOptions.SO_SNDBUF) / 1024,
                    socketChannel.getOption(java.net.StandardSocketOptions.SO_RCVBUF) / 1024);
        } catch (Exception e) {
            log.warn("设置TCP缓冲区失败，使用默认值", e);
        }
        /**/
        // 2、注册SocketChannel，并添加通道附件参数
        SocketChannelContext socketChannelContext = new SocketChannelContext();
        socketChannelContext.setLocalAddress(NioServerContext.getLocalAddress(socketChannel));
        socketChannelContext.setRemoteAddress(NioServerContext.getRemoteAddress(socketChannel));
        socketChannelContext.setChannelPipeLine(new DefaultChannelPipeLine());
        socketChannelContext.setByteBuffer(
                ByteBuffer.allocateDirect(Integer.parseInt(NioServerContext.getValue(BasicConstant.BYTEBUFFER))));
        socketChannelContext.setSocketChannel(socketChannel);

        // 3、注册通道数据处理器
        socketChannelContext.setChannelFlag(BasicConstant.FILE_CHANNEL_CONTEXT);
        socketChannelContext.getChannelPipeLine().addHandler(
                new SimpleChannelContext(socketChannelContext.getChannelPipeLine()), new TextTransmissionHandler()); // 文本传输处理器（用户认证+目录操作）
        socketChannelContext.getChannelPipeLine().addHandler(
                new SimpleChannelContext(socketChannelContext.getChannelPipeLine()), new FileUploadHandler()); // 文件上传处理器
        socketChannelContext.getChannelPipeLine().addHandler(
                new SimpleChannelContext(socketChannelContext.getChannelPipeLine()), new FileRangePullHandler()); // Pull-Range 在线流处理器
        socketChannelContext.getChannelPipeLine().addHandler(
                new SimpleChannelContext(socketChannelContext.getChannelPipeLine()), new FileDownloadHandler()); // 文件下载处理器
        return socketChannelContext;
    }
}
