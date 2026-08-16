package com.alibaba.server.nio.tls;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 在 net-server 进程内终止 TLS，并把解密后的字节原样转发到现有回环后端。
 */
public final class EmbeddedTlsGateway implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(EmbeddedTlsGateway.class);
    private static final String[] TLS_PROTOCOLS = {"TLSv1.2"};
    private static final int LISTENER_BACKLOG = 128;
    private static final int EXECUTOR_KEEP_ALIVE_SECONDS = 60;
    private static final int STOP_WAIT_MILLIS = 3_000;
    private static final long REJECTION_WARNING_INTERVAL_MILLIS = 10_000L;
    private static final String THREAD_NAME_PREFIX = "net-server-tls-";

    private final Object lifecycleMonitor = new Object();
    private final InetAddress bindAddress;
    private final List<TlsGatewayEndpoint> endpoints;
    private final SSLServerSocketFactory socketFactory;
    private final int handshakeTimeoutMillis;
    private final int connectTimeoutMillis;
    private final int idleTimeoutMillis;
    private final int maxConnections;
    private final int bufferSize;
    private final Consumer<Throwable> fatalFailureHandler;
    private final Map<String, ListenerRegistration> listeners = new LinkedHashMap<>();
    private final List<Thread> acceptorThreads = new ArrayList<>();
    private final Set<TlsSocketRelay.Connection> activeConnections =
            Collections.newSetFromMap(new ConcurrentHashMap<TlsSocketRelay.Connection, Boolean>());
    private final AtomicLong lastRejectionWarningMillis = new AtomicLong(0L);
    private final AtomicBoolean fatalFailureReported = new AtomicBoolean(false);

    private volatile boolean prepared;
    private volatile boolean running;
    private volatile ThreadPoolExecutor connectionExecutor;
    private volatile ThreadPoolExecutor reverseExecutor;

    /**
     * 使用已校验配置创建内置 TLS Gateway。
     *
     * @param config TLS Gateway 配置
     * @param sslContext 服务端 TLS 上下文
     */
    public EmbeddedTlsGateway(TlsGatewayConfig config, SSLContext sslContext) {
        this(config, sslContext, exception -> {
        });
    }

    /**
     * 使用已校验配置创建内置 TLS Gateway，并把运行期监听故障交给进程生命周期所有者。
     */
    public EmbeddedTlsGateway(
            TlsGatewayConfig config,
            SSLContext sslContext,
            Consumer<Throwable> fatalFailureHandler) {
        this(
                requireEnabled(config).getBindAddress(),
                config.getEndpoints(),
                sslContext,
                config.getHandshakeTimeoutMillis(),
                config.getConnectTimeoutMillis(),
                config.getIdleTimeoutMillis(),
                config.getMaxConnections(),
                config.getBufferSize(),
                fatalFailureHandler);
    }

    // [修改] 包内构造器允许集成测试使用回环地址和随机公网端口，不放宽生产配置校验。
    EmbeddedTlsGateway(
            InetAddress bindAddress,
            List<TlsGatewayEndpoint> endpoints,
            SSLContext sslContext,
            int handshakeTimeoutMillis,
            int connectTimeoutMillis,
            int idleTimeoutMillis,
            int maxConnections,
            int bufferSize) {
        this(
                bindAddress,
                endpoints,
                sslContext,
                handshakeTimeoutMillis,
                connectTimeoutMillis,
                idleTimeoutMillis,
                maxConnections,
                bufferSize,
                exception -> {
                });
    }

    EmbeddedTlsGateway(
            InetAddress bindAddress,
            List<TlsGatewayEndpoint> endpoints,
            SSLContext sslContext,
            int handshakeTimeoutMillis,
            int connectTimeoutMillis,
            int idleTimeoutMillis,
            int maxConnections,
            int bufferSize,
            Consumer<Throwable> fatalFailureHandler) {
        this.bindAddress = Objects.requireNonNull(bindAddress, "bindAddress");
        this.endpoints = immutableEndpoints(endpoints);
        this.socketFactory = Objects.requireNonNull(sslContext, "sslContext").getServerSocketFactory();
        this.handshakeTimeoutMillis = requirePositive(handshakeTimeoutMillis, "handshakeTimeoutMillis");
        this.connectTimeoutMillis = requirePositive(connectTimeoutMillis, "connectTimeoutMillis");
        this.idleTimeoutMillis = requirePositive(idleTimeoutMillis, "idleTimeoutMillis");
        this.maxConnections = requirePositive(maxConnections, "maxConnections");
        this.bufferSize = requirePositive(bufferSize, "bufferSize");
        this.fatalFailureHandler = Objects.requireNonNull(
                fatalFailureHandler,
                "fatalFailureHandler");
    }

    /**
     * 一次性预绑定全部 TLS 端口，后续端口失败时回滚已绑定端口。
     */
    public void prepare() {
        synchronized (lifecycleMonitor) {
            if (prepared) {
                return;
            }
            Map<String, ListenerRegistration> preparing = new LinkedHashMap<>();
            try {
                for (TlsGatewayEndpoint endpoint : endpoints) {
                    SSLServerSocket listener = createListener(endpoint);
                    preparing.put(endpoint.getName(), new ListenerRegistration(endpoint, listener));
                }
                listeners.putAll(preparing);
                fatalFailureReported.set(false);
                prepared = true;
            } catch (IOException | RuntimeException exception) {
                closeListeners(preparing.values());
                listeners.clear();
                prepared = false;
                if (exception instanceof TlsGatewayStartupException) {
                    throw (TlsGatewayStartupException) exception;
                }
                throw new TlsGatewayStartupException("预绑定 TLS Gateway 端口失败", exception);
            }
        }
    }

    /**
     * 启动每个端点的 Acceptor 和受限转发线程池。
     */
    public void start() {
        synchronized (lifecycleMonitor) {
            if (running) {
                return;
            }
            if (!prepared) {
                throw new IllegalStateException("TLS Gateway 必须先 prepare 再 start");
            }
            connectionExecutor = newRelayExecutor("connection", maxConnections);
            reverseExecutor = newRelayExecutor("reverse", maxConnections);
            running = true;
            try {
                for (ListenerRegistration registration : listeners.values()) {
                    Thread acceptor = new Thread(
                            () -> acceptLoop(registration),
                            THREAD_NAME_PREFIX + "acceptor-" + registration.endpoint.getName());
                    acceptor.setDaemon(true);
                    acceptorThreads.add(acceptor);
                    acceptor.start();
                }
                LOGGER.info(
                        "内置 TLS Gateway 已启动，bindAddress={}, endpoints={}, maxConnections={}",
                        bindAddress.getHostAddress(),
                        listeners.keySet(),
                        maxConnections);
            } catch (RuntimeException exception) {
                running = false;
                throw new TlsGatewayStartupException("启动 TLS Gateway Acceptor 失败", exception);
            }
        }
    }

    /**
     * 返回指定端点实际绑定的公网端口，测试随机端口时会返回系统分配值。
     *
     * @param endpointName 端点名
     * @return 实际监听端口
     */
    public int getBoundPort(String endpointName) {
        synchronized (lifecycleMonitor) {
            ListenerRegistration registration = listeners.get(endpointName);
            if (registration == null) {
                throw new IllegalArgumentException("TLS Gateway 端点未绑定: " + endpointName);
            }
            return registration.listener.getLocalPort();
        }
    }

    /**
     * 关闭监听端口、活动连接和所有 Gateway 线程；重复调用不会重复释放资源。
     */
    public void stop() {
        synchronized (lifecycleMonitor) {
            if (!prepared
                    && !running
                    && listeners.isEmpty()
                    && activeConnections.isEmpty()
                    && connectionExecutor == null
                    && reverseExecutor == null) {
                return;
            }
            running = false;
            prepared = false;
            List<ListenerRegistration> stoppingListeners = new ArrayList<>(listeners.values());
            List<Thread> stoppingAcceptors = new ArrayList<>(acceptorThreads);
            ThreadPoolExecutor stoppingConnections = connectionExecutor;
            ThreadPoolExecutor stoppingReverse = reverseExecutor;
            listeners.clear();
            acceptorThreads.clear();
            connectionExecutor = null;
            reverseExecutor = null;

            // [修改] stop 全程持有生命周期锁，旧代清理完成前不允许新代 prepare/start。
            closeListeners(stoppingListeners);
            for (TlsSocketRelay.Connection connection : new ArrayList<>(activeConnections)) {
                connection.close();
            }
            shutdownExecutor(stoppingConnections);
            shutdownExecutor(stoppingReverse);
            joinAcceptors(stoppingAcceptors);
            activeConnections.clear();
            LOGGER.info("内置 TLS Gateway 已停止");
        }
    }

    @Override
    public void close() {
        stop();
    }

    private SSLServerSocket createListener(TlsGatewayEndpoint endpoint) throws IOException {
        SSLServerSocket listener = null;
        try {
            listener = (SSLServerSocket) socketFactory.createServerSocket();
            listener.setReuseAddress(true);
            listener.setNeedClientAuth(false);
            listener.setEnabledProtocols(TLS_PROTOCOLS);
            listener.bind(
                    new InetSocketAddress(bindAddress, endpoint.getPublicPort()),
                    LISTENER_BACKLOG);
            return listener;
        } catch (IOException | RuntimeException exception) {
            closeListener(listener);
            throw new TlsGatewayStartupException(
                    "绑定 TLS Gateway 端口失败，endpoint=" + endpoint.getName()
                            + ", bind=" + bindAddress.getHostAddress()
                            + ":" + endpoint.getPublicPort(),
                    exception);
        }
    }

    private void acceptLoop(ListenerRegistration registration) {
        while (running) {
            try {
                SSLSocket clientSocket = (SSLSocket) registration.listener.accept();
                submitConnection(registration, clientSocket);
            } catch (SocketException exception) {
                if (running) {
                    listenerFailed(registration.endpoint, exception);
                }
                return;
            } catch (IOException | RuntimeException exception) {
                if (running) {
                    listenerFailed(registration.endpoint, exception);
                }
                return;
            }
        }
    }

    private void submitConnection(
            ListenerRegistration registration,
            SSLSocket clientSocket) {
        TlsGatewayEndpoint endpoint = registration.endpoint;
        TlsSocketRelay.Connection connection = TlsSocketRelay.connection(
                clientSocket,
                endpoint,
                handshakeTimeoutMillis,
                connectTimeoutMillis,
                idleTimeoutMillis,
                bufferSize);
        synchronized (lifecycleMonitor) {
            ThreadPoolExecutor executor = connectionExecutor;
            ThreadPoolExecutor reverse = reverseExecutor;
            if (!running
                    || listeners.get(endpoint.getName()) != registration
                    || executor == null
                    || reverse == null) {
                connection.close();
                return;
            }
            activeConnections.add(connection);
            try {
                // [修改] 提交连接与 stop 使用同一把锁，旧 Acceptor 不能混入新一代线程池。
                executor.execute(() -> {
                    try {
                        connection.relay(reverse);
                    } finally {
                        activeConnections.remove(connection);
                    }
                });
            } catch (RejectedExecutionException exception) {
                activeConnections.remove(connection);
                connection.close();
                warnRejectedConnection(endpoint, clientSocket);
            }
        }
    }

    private void warnRejectedConnection(TlsGatewayEndpoint endpoint, SSLSocket clientSocket) {
        long now = System.currentTimeMillis();
        long previous = lastRejectionWarningMillis.get();
        if (now - previous < REJECTION_WARNING_INTERVAL_MILLIS
                || !lastRejectionWarningMillis.compareAndSet(previous, now)) {
            return;
        }
        LOGGER.warn(
                "TLS Gateway 达到连接上限，拒绝新连接，endpoint={}, remote={}, maxConnections={}",
                endpoint.getName(),
                clientSocket.getRemoteSocketAddress(),
                maxConnections);
    }

    private void listenerFailed(TlsGatewayEndpoint endpoint, Throwable exception) {
        if (!fatalFailureReported.compareAndSet(false, true)) {
            return;
        }
        LOGGER.error(
                "TLS Gateway 监听异常，停止全部公网端口，endpoint={}, bind={}:{}, exception={}",
                endpoint.getName(),
                bindAddress.getHostAddress(),
                endpoint.getPublicPort(),
                exception.getClass().getSimpleName(),
                exception);
        stop();
        try {
            // [修改] Gateway 只负责清理资源，进程是否退出由 NioServerContext 统一决定。
            fatalFailureHandler.accept(exception);
        } catch (RuntimeException handlerException) {
            LOGGER.error("TLS Gateway 监听故障通知生命周期所有者失败", handlerException);
        }
    }

    private ThreadPoolExecutor newRelayExecutor(String role, int maximumThreads) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                0,
                maximumThreads,
                EXECUTOR_KEEP_ALIVE_SECONDS,
                TimeUnit.SECONDS,
                new SynchronousQueue<Runnable>(),
                new NamedThreadFactory(THREAD_NAME_PREFIX + role + "-"),
                new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private void shutdownExecutor(ThreadPoolExecutor executor) {
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(STOP_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                LOGGER.warn("TLS Gateway 转发线程池未在 {}ms 内退出", STOP_WAIT_MILLIS);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LOGGER.warn("等待 TLS Gateway 转发线程退出时被中断");
        }
    }

    private void joinAcceptors(List<Thread> threads) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STOP_WAIT_MILLIS);
        for (Thread thread : threads) {
            if (thread == Thread.currentThread()) {
                continue;
            }
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                break;
            }
            try {
                TimeUnit.NANOSECONDS.timedJoin(thread, remainingNanos);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                LOGGER.warn("等待 TLS Gateway Acceptor 退出时被中断");
                return;
            }
        }
    }

    private void closeListeners(Iterable<ListenerRegistration> registrations) {
        for (ListenerRegistration registration : registrations) {
            closeListener(registration.listener);
        }
    }

    private void closeListener(SSLServerSocket listener) {
        if (listener == null) {
            return;
        }
        try {
            listener.close();
        } catch (IOException exception) {
            LOGGER.debug(
                    "关闭 TLS Gateway 监听端口失败，port={}, exception={}",
                    listener.getLocalPort(),
                    exception.getClass().getSimpleName());
        }
    }

    private static TlsGatewayConfig requireEnabled(TlsGatewayConfig config) {
        Objects.requireNonNull(config, "config");
        if (!config.isEnabled()) {
            throw new IllegalArgumentException("TLS Gateway 配置未启用");
        }
        return config;
    }

    private static List<TlsGatewayEndpoint> immutableEndpoints(List<TlsGatewayEndpoint> endpoints) {
        Objects.requireNonNull(endpoints, "endpoints");
        if (endpoints.isEmpty()) {
            throw new IllegalArgumentException("endpoints 不能为空");
        }
        Map<String, TlsGatewayEndpoint> unique = new LinkedHashMap<>();
        for (TlsGatewayEndpoint endpoint : endpoints) {
            Objects.requireNonNull(endpoint, "endpoint");
            if (unique.put(endpoint.getName(), endpoint) != null) {
                throw new IllegalArgumentException("TLS Gateway 端点名重复: " + endpoint.getName());
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(unique.values()));
    }

    private static int requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " 必须大于 0");
        }
        return value;
    }

    private static final class ListenerRegistration {
        private final TlsGatewayEndpoint endpoint;
        private final SSLServerSocket listener;

        private ListenerRegistration(TlsGatewayEndpoint endpoint, SSLServerSocket listener) {
            this.endpoint = endpoint;
            this.listener = listener;
        }
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger sequence = new AtomicInteger(1);

        private NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, prefix + sequence.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
