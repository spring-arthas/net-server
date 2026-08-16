package com.alibaba.server.nio.tls;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * TLS 客户端与本机明文后端之间的字节转发器。
 */
public final class TlsSocketRelay {
    private static final Logger LOGGER = LoggerFactory.getLogger(TlsSocketRelay.class);
    private static final String[] TLS_PROTOCOLS = {"TLSv1.2"};

    private TlsSocketRelay() {
    }

    /**
     * 创建一条客户端到明文后端的双向转发连接。
     *
     * @param clientSocket TLS 客户端连接
     * @param endpoint 后端端点
     * @param handshakeTimeoutMillis TLS 握手超时
     * @param connectTimeoutMillis 后端连接超时
     * @param idleTimeoutMillis 读空闲超时
     * @param bufferSize 单方向复制缓冲区
     * @return 可由 Gateway 统一关闭的连接对象
     */
    static Connection connection(
            SSLSocket clientSocket,
            TlsGatewayEndpoint endpoint,
            int handshakeTimeoutMillis,
            int connectTimeoutMillis,
            int idleTimeoutMillis,
            int bufferSize) {
        return connection(
                clientSocket,
                endpoint,
                handshakeTimeoutMillis,
                connectTimeoutMillis,
                idleTimeoutMillis,
                bufferSize,
                Socket::new);
    }

    static Connection connection(
            SSLSocket clientSocket,
            TlsGatewayEndpoint endpoint,
            int handshakeTimeoutMillis,
            int connectTimeoutMillis,
            int idleTimeoutMillis,
            int bufferSize,
            Supplier<Socket> backendSocketSupplier) {
        return new Connection(
                clientSocket,
                endpoint,
                handshakeTimeoutMillis,
                connectTimeoutMillis,
                idleTimeoutMillis,
                bufferSize,
                backendSocketSupplier);
    }

    static long copy(InputStream input, OutputStream output, int bufferSize) throws IOException {
        if (bufferSize <= 0) {
            throw new IllegalArgumentException("bufferSize 必须大于 0");
        }
        byte[] buffer = new byte[bufferSize];
        long copied = 0;
        int length;
        while ((length = input.read(buffer)) >= 0) {
            if (length == 0) {
                continue;
            }
            // [修改] 只复制原始字节，不解析或重组客户端 FA CE 帧。
            output.write(buffer, 0, length);
            copied += length;
        }
        return copied;
    }

    /**
     * 一条 TLS 客户端连接和一条回环后端连接组成的活动连接对。
     */
    static final class Connection implements AutoCloseable {
        private final SSLSocket clientSocket;
        private final TlsGatewayEndpoint endpoint;
        private final int handshakeTimeoutMillis;
        private final int connectTimeoutMillis;
        private final int idleTimeoutMillis;
        private final int bufferSize;
        private final Supplier<Socket> backendSocketSupplier;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicLong lastActivityNanos = new AtomicLong(System.nanoTime());

        private volatile Socket backendSocket;
        private volatile boolean handshakeComplete;

        private Connection(
                SSLSocket clientSocket,
                TlsGatewayEndpoint endpoint,
                int handshakeTimeoutMillis,
                int connectTimeoutMillis,
                int idleTimeoutMillis,
                int bufferSize,
                Supplier<Socket> backendSocketSupplier) {
            this.clientSocket = Objects.requireNonNull(clientSocket, "clientSocket");
            this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
            this.handshakeTimeoutMillis = handshakeTimeoutMillis;
            this.connectTimeoutMillis = connectTimeoutMillis;
            this.idleTimeoutMillis = idleTimeoutMillis;
            this.bufferSize = bufferSize;
            this.backendSocketSupplier = Objects.requireNonNull(
                    backendSocketSupplier,
                    "backendSocketSupplier");
        }

        /**
         * 完成握手、连接后端并运行两个方向的复制，任一方向结束后关闭整对连接。
         *
         * @param reverseExecutor 后端到客户端方向的执行器
         */
        void relay(Executor reverseExecutor) {
            Objects.requireNonNull(reverseExecutor, "reverseExecutor");
            try {
                configureAndHandshakeClient();
                if (closed.get()) {
                    return;
                }
                Socket connectedBackend = connectBackend();
                if (closed.get()) {
                    return;
                }
                startReverseCopy(reverseExecutor, connectedBackend);
                copyClientToBackend(connectedBackend);
            } catch (SocketTimeoutException exception) {
                if (!closed.get()) {
                    LOGGER.info(
                            "TLS Gateway 连接超时，endpoint={}, remote={}",
                            endpoint.getName(),
                            clientRemoteAddress());
                }
            } catch (SSLException exception) {
                if (!closed.get()) {
                    LOGGER.warn(
                            "TLS Gateway TLS 连接失败，endpoint={}, remote={}, exception={}",
                            endpoint.getName(),
                            clientRemoteAddress(),
                            exception.getClass().getSimpleName());
                }
            } catch (RejectedExecutionException exception) {
                if (!closed.get()) {
                    LOGGER.warn(
                            "TLS Gateway 反向转发线程已满，endpoint={}, remote={}",
                            endpoint.getName(),
                            clientRemoteAddress());
                }
            } catch (IOException exception) {
                if (!closed.get()) {
                    String stage = handshakeComplete ? "转发或后端连接" : "TLS 握手";
                    LOGGER.warn(
                            "TLS Gateway {}失败，endpoint={}, backend={}:{}, remote={}, exception={}",
                            stage,
                            endpoint.getName(),
                            endpoint.getBackendHost(),
                            endpoint.getBackendPort(),
                            clientRemoteAddress(),
                            exception.getClass().getSimpleName());
                }
            } finally {
                close();
            }
        }

        private void configureAndHandshakeClient() throws IOException {
            clientSocket.setUseClientMode(false);
            clientSocket.setEnabledProtocols(TLS_PROTOCOLS);
            clientSocket.setTcpNoDelay(true);
            clientSocket.setKeepAlive(true);
            clientSocket.setSoTimeout(handshakeTimeoutMillis);
            // [修改] TLS 握手在线程池内执行，慢客户端不会阻塞四个 Acceptor。
            clientSocket.startHandshake();
            handshakeComplete = true;
            markActivity();
            clientSocket.setSoTimeout(idleTimeoutMillis);
        }

        private Socket connectBackend() throws IOException {
            Socket backend = Objects.requireNonNull(
                    backendSocketSupplier.get(),
                    "backendSocketSupplier 返回 null");
            backendSocket = backend;
            if (closed.get()) {
                // [修改] stop 可能发生在后端 Socket 创建前，发布后必须再次关闭竞态中新建的 Socket。
                closeSocket(backend, "明文后端");
                throw new SocketException("TLS Gateway 连接已关闭");
            }
            backend.setTcpNoDelay(true);
            backend.setKeepAlive(true);
            // [修改] 只连接配置中已校验过的回环明文端口。
            backend.connect(
                    new InetSocketAddress(endpoint.getBackendHost(), endpoint.getBackendPort()),
                    connectTimeoutMillis);
            backend.setSoTimeout(idleTimeoutMillis);
            markActivity();
            return backend;
        }

        private void startReverseCopy(Executor reverseExecutor, Socket backend) {
            reverseExecutor.execute(() -> {
                try {
                    copyWithSharedIdleTimeout(
                            backend.getInputStream(),
                            clientSocket.getOutputStream());
                } catch (SocketTimeoutException exception) {
                    if (!closed.get()) {
                        LOGGER.info(
                                "TLS Gateway 后端响应超时，endpoint={}, backendPort={}",
                                endpoint.getName(),
                                endpoint.getBackendPort());
                    }
                } catch (IOException exception) {
                    if (!closed.get()) {
                        LOGGER.debug(
                                "TLS Gateway 后端到客户端转发结束，endpoint={}, exception={}",
                                endpoint.getName(),
                                exception.getClass().getSimpleName());
                    }
                } finally {
                    close();
                }
            });
        }

        private void copyClientToBackend(Socket backend) throws IOException {
            // [修改] 两个方向都只复制原始字节，不识别 FA CE 或 HTTP 内容。
            copyWithSharedIdleTimeout(
                    clientSocket.getInputStream(),
                    backend.getOutputStream());
        }

        private void copyWithSharedIdleTimeout(
                InputStream input,
                OutputStream output) throws IOException {
            byte[] buffer = new byte[bufferSize];
            while (!closed.get()) {
                try {
                    int length = input.read(buffer);
                    if (length < 0) {
                        return;
                    }
                    if (length == 0) {
                        continue;
                    }
                    output.write(buffer, 0, length);
                    // [修改] 任一方向有字节流动都会刷新整条连接的共享空闲时间。
                    markActivity();
                } catch (SocketTimeoutException exception) {
                    if (isConnectionIdle()) {
                        throw exception;
                    }
                }
            }
        }

        private void markActivity() {
            lastActivityNanos.set(System.nanoTime());
        }

        private boolean isConnectionIdle() {
            long idleNanos = System.nanoTime() - lastActivityNanos.get();
            return idleNanos >= TimeUnit.MILLISECONDS.toNanos(idleTimeoutMillis);
        }

        private String clientRemoteAddress() {
            return String.valueOf(clientSocket.getRemoteSocketAddress());
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            closeSocket(clientSocket, "TLS 客户端");
            closeSocket(backendSocket, "明文后端");
        }

        private void closeSocket(Socket socket, String side) {
            if (socket == null) {
                return;
            }
            try {
                socket.close();
            } catch (IOException exception) {
                LOGGER.debug(
                        "TLS Gateway 关闭{}连接失败，endpoint={}, exception={}",
                        side,
                        endpoint.getName(),
                        exception.getClass().getSimpleName());
            }
        }
    }
}
