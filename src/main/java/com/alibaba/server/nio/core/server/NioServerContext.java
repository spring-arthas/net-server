package com.alibaba.server.nio.core.server;

import com.alibaba.fastjson.JSON;
import com.alibaba.server.common.BasicConstant;
import com.alibaba.server.nio.tls.EmbeddedTlsGateway;
import com.alibaba.server.nio.tls.TlsBackendReadinessProbe;
import com.alibaba.server.nio.tls.TlsContextFactory;
import com.alibaba.server.nio.tls.TlsGatewayConfig;
import com.alibaba.server.nio.tls.TlsKeyStoreProvisioner;
import com.alibaba.server.nio.tls.TlsNetworkAddressResolver;
import com.alibaba.server.util.LocalTime;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @Auther: YSFY
 * @Date: 2020-11-14 17:09
 * @Pacage_name: com.alibaba.server.nio.core
 * @Project_Name: net-server
 * @Description: 服务上下文管理器
 */

@Slf4j
@SuppressWarnings("all")
public class NioServerContext {

    private static final Object lock = new Object();
    private static final AtomicBoolean TLS_SHUTDOWN_HOOK_REGISTERED = new AtomicBoolean(false);

    private static volatile EmbeddedTlsGateway tlsGateway;

    /**
     * 限速恢复调度器
     * 用于在 ReadEventHandler 暂停 OP_READ 后，定时恢复事件
     * 线程数设为4以支持多个并发连接的限速恢复任务
     */
    private static final java.util.concurrent.ScheduledExecutorService rateLimitScheduler = java.util.concurrent.Executors
            .newScheduledThreadPool(4, new java.util.concurrent.ThreadFactory() {
                private final java.util.concurrent.atomic.AtomicInteger threadNumber = new java.util.concurrent.atomic.AtomicInteger(
                        1);

                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "RATE-LIMIT-SCHEDULER-" + threadNumber.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                }
            });

    /**
     * 获取限速调度器
     */
    public static java.util.concurrent.ScheduledExecutorService getRateLimitScheduler() {
        return rateLimitScheduler;
    }

    /**
     * 启动基础服务以及核心服务
     * 
     * @return
     */
    public static void startupServerContext() {
        EmbeddedTlsGateway preparingGateway = null;
        try {
            // 1、启动基础服务并校验内置 TLS Gateway 配置。
            BasicServer.startupBasicServer();
            TlsGatewayConfig tlsConfig = TlsGatewayConfig.load(BasicServer.getMap(), System::getenv);
            if (tlsConfig.isEnabled()) {
                applyResolvedTlsConfiguration(BasicServer.getMap(), tlsConfig);
                new TlsKeyStoreProvisioner().provision(tlsConfig);
                char[] keyStorePassword = tlsConfig.copyKeyStorePassword();
                try {
                    preparingGateway = new EmbeddedTlsGateway(
                            tlsConfig,
                            new TlsContextFactory().create(
                                    tlsConfig.getKeyStorePath(),
                                    keyStorePassword),
                            NioServerContext::handleTlsGatewayFailure);
                } finally {
                    Arrays.fill(keyStorePassword, '\0');
                }
                // [修改] 先一次性预绑定四个公网端口，任意端口失败都不启动业务后端。
                preparingGateway.prepare();
            }

            // 2、启动 IOC 容器
            startupIocContainer();

            // 3、启动核心服务
            CoreServer.startupCoreServer();

            if (preparingGateway != null) {
                // [修改] 后端真正可连接后才开放 TLS Acceptor，不再依赖固定 sleep。
                TlsBackendReadinessProbe.await(
                        tlsConfig.getEndpoints(),
                        tlsConfig.getConnectTimeoutMillis());
                preparingGateway.start();
                tlsGateway = preparingGateway;
                registerTlsShutdownHook();
            }
        } catch (IOException | RuntimeException exception) {
            if (preparingGateway != null) {
                preparingGateway.stop();
            }
            shutdownTlsGateway();
            // [修改] 启动失败必须传到 main，让单进程部署以非零状态退出。
            log.error("net-server 服务启动失败", exception);
            throw new IllegalStateException("net-server 服务启动失败", exception);
        }
    }

    static void applyResolvedTlsConfiguration(
            Map<String, Object> configuration,
            TlsGatewayConfig tlsConfig) {
        String resolvedAddress = tlsConfig.getBindAddress().getHostAddress();
        if (isAutomaticValue(configuration.get(BasicConstant.TLS_GATEWAY_PUBLIC_IP))) {
            configuration.put(BasicConstant.TLS_GATEWAY_PUBLIC_IP, resolvedAddress);
        }
        if (isAutomaticValue(configuration.get(BasicConstant.MEDIA_STREAM_PUBLIC_HOST))) {
            configuration.put(BasicConstant.MEDIA_STREAM_PUBLIC_HOST, resolvedAddress);
        }
        log.info("TLS 运行环境已解析: os={}, address={}, keyStore={}",
                System.getProperty(BasicConstant.OS_NAME),
                resolvedAddress,
                tlsConfig.getKeyStorePath());
    }

    private static boolean isAutomaticValue(Object value) {
        return value == null || TlsNetworkAddressResolver.isAutomatic(value.toString());
    }

    /**
     * 关闭内置 TLS Gateway，供 JVM shutdown hook 和启动失败清理复用。
     */
    public static void shutdownTlsGateway() {
        EmbeddedTlsGateway stoppingGateway = tlsGateway;
        tlsGateway = null;
        if (stoppingGateway != null) {
            stoppingGateway.stop();
        }
    }

    static void handleTlsGatewayFailure(Throwable exception) {
        // [修改] 四个公网 TLS 端口属于单进程核心能力，监听失效后必须让守护进程重启 Java。
        log.error("TLS Gateway 监听发生致命故障，net-server 将以退出码 1 结束", exception);
        shutdownTlsGateway();
        System.exit(1);
    }

    private static void registerTlsShutdownHook() {
        if (!TLS_SHUTDOWN_HOOK_REGISTERED.compareAndSet(false, true)) {
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(
                NioServerContext::shutdownTlsGateway,
                "NET-SERVER-TLS-SHUTDOWN"));
    }

    /**
     * 创建Selector
     * 
     * @return selector
     */
    public static Selector openSelector() throws IOException {
        return Selector.open();
    }

    /**
     * 根据不同服务名称获取其对应的Selector
     * 
     * @param selectorName
     * @return selector
     */
    public static Selector getSelector(String selectorName) {
        Map<String, Object> cacheMap = BasicServer.getMap();
        if (!cacheMap.isEmpty() && cacheMap.containsKey(BasicConstant.SELECTOR)) {
            Map<String, Object> assignMap = (Map) cacheMap.get(BasicConstant.SELECTOR);
            if (!assignMap.isEmpty() && assignMap.containsKey(selectorName)) {
                return (Selector) ((Map) assignMap.get(selectorName)).get(selectorName);
            }
        }

        return null;
    }

    /**
     * 根据key获取配置文件数据
     * 
     * @param param
     * @return object
     */
    public static String getValue(String param) {
        Map<String, Object> cacheMap = BasicServer.getMap();
        if (!cacheMap.isEmpty() && cacheMap.containsKey(param)) {
            return cacheMap.get(param).toString();
        }

        return "";
    }

    /**
     * 获取当前请求的服务端端口号，根据端口号判断具体属于哪类请求
     *
     * @param selectionKey
     * @return
     * @throws IOException
     */
    public static String getPort(SelectionKey selectionKey) throws IOException {
        return String.valueOf(
                ((InetSocketAddress) ((ServerSocketChannel) selectionKey.channel()).getLocalAddress()).getPort());
    }

    /**
     * 根据SocketChannel获取远程连接信息(ip:port)
     * 
     * @param socketChannel
     * @return
     */
    public static String getRemoteAddress(SocketChannel socketChannel) {
        String ip = "";
        Integer port = 0;
        try {
            synchronized (lock) {
                ip = ((InetSocketAddress) socketChannel.getRemoteAddress()).getAddress().getHostAddress();
                port = ((InetSocketAddress) socketChannel.getRemoteAddress()).getPort();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return (ip + ":" + port);
    }

    /**
     * 根据SocketChannel获取本地连接信息(ip:port)
     * 
     * @param socketChannel
     * @return
     */
    public static String getLocalAddress(SocketChannel socketChannel) {
        String ip = "";
        Integer port = 0;
        try {
            ip = ((InetSocketAddress) socketChannel.getLocalAddress()).getAddress().getHostAddress();
            port = ((InetSocketAddress) socketChannel.getLocalAddress()).getPort();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return (ip + ":" + port);
    }

    /**
     * 获取服务端本地地址
     * 
     * @param socketChannel
     * @return
     */
    public static String getServerLocalAddress(ServerSocketChannel serverSocketChannel) {
        String ip = "";
        Integer port = 0;
        try {
            ip = ((InetSocketAddress) serverSocketChannel.getLocalAddress()).getAddress().getHostAddress();
            port = ((InetSocketAddress) serverSocketChannel.getLocalAddress()).getPort();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return (ip + ":" + port);
    }

    /**
     * Selector 可选事件注册
     * 
     * @param socketChannel
     * @param selector
     * @param opt
     * @return
     * @throws IOException
     */
    public static SelectionKey EventRegister(SocketChannel socketChannel, Selector selector, final int opt)
            throws IOException {
        if (!selector.isOpen()) {
            log.error(
                    "[ " + LocalTime.formatDate(LocalDateTime.now())
                            + " ] NioServerContext | --> Selector is not open yet, threadName = {}",
                    Thread.currentThread().getName());
            return null;
        }

        socketChannel.configureBlocking(false);
        selector.wakeup();
        return socketChannel.register(selector, opt);
    }

    /**
     * 释放通道资源
     * 
     * @param socketChannel
     * @return Boolean 关闭结果
     */
    public static Boolean closedAndRelease(SocketChannel socketChannel) {
        if (!Optional.ofNullable(socketChannel).isPresent()) {
            return false;
        }
        com.alibaba.server.nio.service.file.handler.TextTransmissionHandler.cleanupSocketChannel(socketChannel);
        if (!socketChannel.isOpen()) {
            log.info("NioServerContext: SocketChannel已关闭, 忽略本次资源释放请求");
            return true;
        }
        try {
            String remoteAddress = NioServerContext.getRemoteAddress(socketChannel);
            String localAddress = NioServerContext.getLocalAddress(socketChannel);
            // 1、清理该远程连接对应的上传/下载/文本任务
            com.alibaba.server.nio.service.file.handler.FileUploadHandler.cleanupConnection(remoteAddress);
            com.alibaba.server.nio.service.file.handler.FileDownloadHandler.cleanupConnection(remoteAddress);
            com.alibaba.server.nio.service.file.handler.FileRangePullHandler.cleanupConnection(remoteAddress);
            com.alibaba.server.nio.service.file.handler.TextTransmissionHandler.cleanupConnection(remoteAddress);
            // 2、关闭socketChannel，将会发送流截至符 -1到客户端
            Socket socket = socketChannel.socket();
            if (!socket.isClosed()) {
                socketChannel.shutdownInput();
                socketChannel.shutdownOutput();
                socketChannel.close();
            }
            log.info(
                    "NioServerContext: 服务端socketChannel关闭成功, 资源已释放, 本次通道连接信息：remoteAddress = {}, localAddress = {}, thread = {}",
                    remoteAddress, localAddress, Thread.currentThread().getName());
            return true;
        } catch (Exception e) {
            log.error("NioServerContext: 服务端通道socketChannel资源释放出现异常, 通道信息 = {}, error = {}",
                    JSON.toJSONString(socketChannel),
                    ExceptionUtils.getStackTrace(e));
        } finally {

        }

        return false;
    }

    /**
     * 获取IOC容器对象
     * 
     * @param cls
     * @return
     */
    public static Object getObjectByType(Class cls) {
        ClassPathXmlApplicationContext context = BasicServer.classPathXmlApplicationContext;
        if (Optional.ofNullable(context).isPresent()) {
            return context.getBean(cls);
        }

        return null;
    }

    /**
     * 获取 FileService 实例
     * 
     * @return FileService
     */
    public static com.alibaba.server.nio.repository.file.service.FileService getFileService() {
        return (com.alibaba.server.nio.repository.file.service.FileService) getObjectByType(
                com.alibaba.server.nio.repository.file.service.FileService.class);
    }

    /**
     * SocketChannel IOException异常重连
     * 
     * @param socketChannel
     * @return Boolean
     */
    public static Boolean reConnected(SocketChannel socketChannel) {
        if (socketChannel.isConnected()) {
            // 如果由于网络抖动，可能直接又连接上，则直接返回true
            return Boolean.TRUE;
        }

        Integer index = 1;
        Boolean reconnected = Boolean.FALSE;
        Integer reconnectedCount = Integer.valueOf(NioServerContext.getValue(BasicConstant.SOCKET_RECONNECTED_COUNT));
        // 尝试重连
        while (index <= reconnectedCount) {
            log.info(
                    "[" + Thread.currentThread().getName()
                            + " ] NioServerContext | --> {} reconnected..., address = {}, thread = {}",
                    index, NioServerContext.getLocalAddress(socketChannel), Thread.currentThread().getName());

            try {
                socketChannel.connect((InetSocketAddress) socketChannel.getRemoteAddress());
                if (!socketChannel.isConnected()) {
                    // 此处连接不上，直接等待socket超时
                    while (socketChannel.finishConnect()) {
                        reconnected = Boolean.TRUE;
                        break;
                    }
                } else {
                    reconnected = Boolean.TRUE;
                }
            } catch (IOException e) {
                if (e instanceof SocketTimeoutException) {
                    // socket连接超时异常,尝试下次连接
                    index = index + 1;
                    continue;
                }
            }

            if (Boolean.TRUE.equals(reconnected)) {
                log.info(
                        "[" + Thread.currentThread().getName()
                                + " ] NioServerContext | --> {} reconnected success, address = {}, thread = {}",
                        index, NioServerContext.getLocalAddress(socketChannel), Thread.currentThread().getName());
                break;
            }
        }

        // 判断如果达到最大重连次数后还是无法连接上，则关闭当前SocketChannel文件描述符，释放资源
        if (index > reconnectedCount && Optional.ofNullable(socketChannel).isPresent()) {
            try {
                socketChannel.shutdownInput();
                socketChannel.shutdownOutput();
                socketChannel.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        return reconnected;
    }

    /**
     * 启动IOC容器
     */
    public static void startupIocContainer() throws IOException {
        BasicServer.classPathXmlApplicationContext = new ClassPathXmlApplicationContext(
                "spring/applicationContext.xml");
        log.info("net server IOC container初始化成功, threadName = {}", Thread.currentThread().getName());
    }

    /**
     * 获取BasicServer Map中的元素
     *
     * @param key      集合大key
     * @param goOn     是否对大类key返回的value节序遍历,基本为Map类型才会进行继续遍历
     * @param childKey 当goOn为true时指定二次遍历时的key
     * @return optional
     */
    public static Optional<Object> getAssignValue(String key, Boolean goOn, String childKey) {
        Map map = BasicServer.getMap();
        if (CollectionUtils.isEmpty(map) || !map.containsKey(key)) {
            return null;
        }

        Object obj = map.get(key);
        if (!Optional.ofNullable(obj).isPresent()) {
            return null;
        }

        if (obj instanceof java.util.Map) {
            if (!goOn) {
                return Optional.of(obj);
            }

            Map<String, Object> innerMap = (Map<String, Object>) obj;
            if (CollectionUtils.isEmpty(innerMap) || !innerMap.containsKey(childKey)) {
                return null;
            }

            return Optional.of(innerMap.get(childKey));
        }

        if (obj instanceof java.util.List || obj instanceof java.util.List || obj instanceof java.lang.String) {
            return Optional.of(obj);
        }

        return null;
    }
}
