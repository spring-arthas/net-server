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
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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

    private static final long SOCKET_WARNING_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(5);
    private static final ConcurrentMap<String, AtomicLong> LAST_SOCKET_WARNING_TIMES = new ConcurrentHashMap<>();

    @FunctionalInterface
    protected interface AcceptedSocketChannelRegistrar {
        void register(SocketChannel socketChannel) throws IOException;
    }

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
     * 接收并注册当前等待队列中的客户端连接。
     *
     * @param serverSocketChannel 服务端监听通道
     * @param registrar 单连接注册逻辑
     * @param connectionType 连接类型，用于异常日志
     * @return 成功注册的连接数
     * @throws IOException 服务端监听通道接收失败
     */
    protected int acceptPendingConnections(
            ServerSocketChannel serverSocketChannel,
            AcceptedSocketChannelRegistrar registrar,
            String connectionType) throws IOException {
        SocketChannel socketChannel;
        int acceptedCount = 0;
        while ((socketChannel = serverSocketChannel.accept()) != null) {
            try {
                registrar.register(socketChannel);
                acceptedCount++;
            } catch (IOException registrationException) {
                // [修改] 单个客户端在初始化阶段断开时，只关闭当前连接，
                // Acceptor 线程继续接收后续连接。
                closeFailedSocketChannel(socketChannel, connectionType, registrationException);
            }
        }
        return acceptedCount;
    }

    private void closeFailedSocketChannel(
            SocketChannel socketChannel,
            String connectionType,
            IOException registrationException) {
        String channelDescription = String.valueOf(socketChannel);
        try {
            socketChannel.close();
        } catch (IOException closeException) {
            registrationException.addSuppressed(closeException);
        }
        // [修改] Socket 异常首次告警，5 分钟内重复事件降为 TRACE，
        // 兼顾真实故障发现和健康检查降噪。
        if (registrationException instanceof SocketException) {
            if (shouldLogSocketWarning(connectionType)) {
                log.warn("{}客户端连接初始化失败，已关闭当前连接并继续接收；"
                                + "同类异常 5 分钟内降为 TRACE, channel={}, error={}",
                        connectionType, channelDescription, registrationException.getMessage(), registrationException);
            } else {
                log.trace("{}客户端在初始化阶段断开，已关闭当前连接并继续接收, "
                                + "channel={}, error={}",
                        connectionType, channelDescription, registrationException.getMessage());
            }
            return;
        }
        log.warn("{}客户端连接初始化失败，已关闭当前连接并继续接收后续连接, "
                        + "channel={}, error={}",
                connectionType, channelDescription, registrationException.getMessage(), registrationException);
    }

    private boolean shouldLogSocketWarning(String connectionType) {
        AtomicLong lastWarningTime = LAST_SOCKET_WARNING_TIMES.computeIfAbsent(
                connectionType, ignored -> new AtomicLong());
        while (true) {
            long now = System.currentTimeMillis();
            long previous = lastWarningTime.get();
            if (previous > 0 && now - previous < SOCKET_WARNING_INTERVAL_MILLIS) {
                return false;
            }
            if (lastWarningTime.compareAndSet(previous, now)) {
                return true;
            }
        }
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
        configureAcceptedSocket(socketChannel);
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

    protected void configureAcceptedSocket(SocketChannel socketChannel) throws IOException {
        // [修改] NIO 通道保持非阻塞，小帧立即发送，避免登录和聊天消息受 Nagle 延迟。
        socketChannel.configureBlocking(false);
        socketChannel.socket().setKeepAlive(true);
        socketChannel.socket().setTcpNoDelay(true);
        // [修改] 禁用 SO_LINGER，关闭连接不能阻塞 Acceptor/Selector 线程。
        socketChannel.socket().setSoLinger(false, 0);
        // [修改] 非阻塞 SocketChannel 的读超时由应用心跳和内置 TLS Gateway 空闲超时负责。
        socketChannel.socket().setSoTimeout(0);
    }
}
