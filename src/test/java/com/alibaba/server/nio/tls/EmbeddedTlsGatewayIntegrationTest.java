package com.alibaba.server.nio.tls;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.io.DataInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

// [修改] 覆盖四端口 TLS 监听、容量限制和完整生命周期，不接触现有业务帧解析代码。
public class EmbeddedTlsGatewayIntegrationTest {
    private static final String KEY_STORE_PASSWORD = "changeit";
    private static final int TEST_TIMEOUT_MILLIS = 2_000;
    private static final int TEST_BUFFER_SIZE = 4_096;

    private static TemporaryFolder keyStoreFolder;
    private static SSLContext serverContext;
    private static SSLContext clientContext;

    @BeforeClass
    public static void createTlsContexts() throws Exception {
        keyStoreFolder = new TemporaryFolder();
        keyStoreFolder.create();
        Path keyStore = TlsTestKeyStore.create(keyStoreFolder, KEY_STORE_PASSWORD);
        serverContext = new TlsContextFactory().create(
                keyStore,
                KEY_STORE_PASSWORD.toCharArray());
        clientContext = TlsTestKeyStore.createClientContext(
                keyStore,
                KEY_STORE_PASSWORD.toCharArray());
    }

    @AfterClass
    public static void deleteTlsContexts() {
        if (keyStoreFolder != null) {
            keyStoreFolder.delete();
        }
    }

    @Test
    public void relaysLargeFragmentedTlsPayloadExactly() throws Exception {
        try (EchoBackend backend = new EchoBackend()) {
            EmbeddedTlsGateway gateway = gateway(
                    Arrays.asList(endpoint("control", backend.getPort())),
                    4);
            try {
                gateway.prepare();
                gateway.start();

                byte[] request = new byte[196_733];
                new Random(47L).nextBytes(request);
                request[0] = (byte) 0xFA;
                request[1] = (byte) 0xCE;

                byte[] response = roundTripFragmented(
                        gateway.getBoundPort("control"),
                        request,
                        137);

                assertArrayEquals(request, response);
            } finally {
                gateway.stop();
            }
        }
    }

    @Test
    public void bindsAndRelaysAllFourMappings() throws Exception {
        List<EchoBackend> backends = Arrays.asList(
                new EchoBackend(),
                new EchoBackend(),
                new EchoBackend(),
                new EchoBackend());
        EmbeddedTlsGateway gateway = gateway(Arrays.asList(
                endpoint("control", backends.get(0).getPort()),
                endpoint("upload", backends.get(1).getPort()),
                endpoint("download", backends.get(2).getPort()),
                endpoint("media", backends.get(3).getPort())), 8);
        try {
            gateway.prepare();
            gateway.start();

            assertRoundTrip(gateway, "control", new byte[]{(byte) 0xFA, (byte) 0xCE, 0x01});
            assertRoundTrip(gateway, "upload", new byte[]{(byte) 0xFA, (byte) 0xCE, 0x02});
            assertRoundTrip(gateway, "download", new byte[]{(byte) 0xFA, (byte) 0xCE, 0x03});
            assertRoundTrip(gateway, "media", "GET /media HTTP/1.1\r\n\r\n".getBytes("UTF-8"));
        } finally {
            gateway.stop();
            for (EchoBackend backend : backends) {
                backend.close();
            }
        }
    }

    @Test
    public void rollsBackEarlierListenerWhenLaterBindFails() throws Exception {
        int firstPort = findFreePort();
        try (ServerSocket occupied = new ServerSocket()) {
            occupied.setReuseAddress(false);
            occupied.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            int occupiedPort = occupied.getLocalPort();
            EmbeddedTlsGateway gateway = gateway(Arrays.asList(
                    new TlsGatewayEndpoint("control", firstPort, "127.0.0.1", 1),
                    new TlsGatewayEndpoint("upload", occupiedPort, "127.0.0.1", 1)), 4);
            try {
                gateway.prepare();
                fail("expected TlsGatewayStartupException");
            } catch (TlsGatewayStartupException exception) {
                assertTrue(exception.getMessage().contains("upload"));
            } finally {
                gateway.stop();
            }

            try (ServerSocket rebound = new ServerSocket()) {
                rebound.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), firstPort));
                assertEquals(firstPort, rebound.getLocalPort());
            }
        }
    }

    @Test
    public void rejectsNewConnectionWhenConnectionLimitIsReached() throws Exception {
        try (EchoBackend backend = new EchoBackend()) {
            EmbeddedTlsGateway gateway = gateway(
                    Arrays.asList(endpoint("control", backend.getPort())),
                    1);
            SSLSocket firstClient = null;
            SSLSocket rejectedClient = null;
            try {
                gateway.prepare();
                gateway.start();
                int publicPort = gateway.getBoundPort("control");

                firstClient = connect(publicPort);
                assertSocketRoundTrip(firstClient, new byte[]{0x01});

                rejectedClient = newClient(publicPort);
                try {
                    rejectedClient.startHandshake();
                    fail("expected second TLS connection to be rejected");
                } catch (IOException expected) {
                    assertFalse(expected instanceof SocketTimeoutException);
                }

                assertSocketRoundTrip(firstClient, new byte[]{0x02});
            } finally {
                closeQuietly(rejectedClient);
                closeQuietly(firstClient);
                gateway.stop();
            }
        }
    }

    @Test
    public void stopClosesActiveSockets() throws Exception {
        try (EchoBackend backend = new EchoBackend()) {
            EmbeddedTlsGateway gateway = gateway(
                    Arrays.asList(endpoint("control", backend.getPort())),
                    2);
            SSLSocket client = null;
            try {
                gateway.prepare();
                gateway.start();
                client = connect(gateway.getBoundPort("control"));
                assertSocketRoundTrip(client, new byte[]{0x11});

                gateway.stop();

                assertPeerClosed(client);
            } finally {
                closeQuietly(client);
                gateway.stop();
            }
        }
    }

    @Test
    public void oneWayTrafficKeepsConnectionAlivePastIdleTimeout() throws Exception {
        byte[] streamed = new byte[12];
        for (int index = 0; index < streamed.length; index++) {
            streamed[index] = (byte) index;
        }
        try (StreamingBackend backend = new StreamingBackend(streamed, 50L)) {
            EmbeddedTlsGateway gateway = gateway(
                    Arrays.asList(endpoint("download", backend.getPort())),
                    2,
                    150);
            try {
                gateway.prepare();
                gateway.start();
                try (SSLSocket client = connect(gateway.getBoundPort("download"))) {
                    byte[] response = new byte[streamed.length];
                    new DataInputStream(client.getInputStream()).readFully(response);
                    assertArrayEquals(streamed, response);
                }
            } finally {
                gateway.stop();
            }
        }
    }

    @Test
    public void repeatedPrepareStartAndStopDoesNotLeakThreads() throws Exception {
        try (EchoBackend backend = new EchoBackend()) {
            EmbeddedTlsGateway gateway = gateway(
                    Arrays.asList(endpoint("control", backend.getPort())),
                    2);
            for (int run = 0; run < 3; run++) {
                gateway.prepare();
                gateway.prepare();
                gateway.start();
                gateway.start();
                assertRoundTrip(gateway, "control", new byte[]{(byte) run});
                gateway.stop();
                gateway.stop();
            }

            assertNoGatewayThreadsRemain();
        }
    }

    @Test
    public void notifiesLifecycleOwnerAfterUnexpectedListenerFailure() throws Exception {
        CountDownLatch failureReported = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        EmbeddedTlsGateway gateway = gateway(
                Arrays.asList(endpoint("control", 1)),
                2,
                TEST_TIMEOUT_MILLIS,
                exception -> {
                    failure.set(exception);
                    failureReported.countDown();
                });
        try {
            gateway.prepare();
            gateway.start();
            int publicPort = gateway.getBoundPort("control");

            // [修改] 模拟运行中的公网监听 Socket 异常关闭，不走正常 stop 路径。
            closeListenerUnexpectedly(gateway, "control");

            assertTrue("监听失败没有通知生命周期所有者",
                    failureReported.await(2, TimeUnit.SECONDS));
            assertTrue(failure.get() instanceof IOException);
            assertPortClosed(publicPort);
        } finally {
            gateway.stop();
        }
    }

    @Test
    public void nextPrepareWaitsUntilPreviousStopFinishes() throws Exception {
        try (EchoBackend backend = new EchoBackend()) {
            EmbeddedTlsGateway gateway = gateway(
                    Arrays.asList(endpoint("control", backend.getPort())),
                    2);
            CountDownLatch releaseOldAcceptor = new CountDownLatch(1);
            CountDownLatch restarted = new CountDownLatch(1);
            AtomicReference<Throwable> restartFailure = new AtomicReference<>();
            Thread oldAcceptor = new Thread(
                    () -> awaitLatch(releaseOldAcceptor),
                    "test-old-tls-generation");
            Thread stopThread = new Thread(gateway::stop, "test-tls-stop-generation");
            Thread restartThread = new Thread(() -> {
                try {
                    gateway.prepare();
                    gateway.start();
                    restarted.countDown();
                } catch (Throwable exception) {
                    restartFailure.set(exception);
                }
            }, "test-tls-restart-generation");
            try {
                gateway.prepare();
                gateway.start();
                oldAcceptor.start();
                addAcceptorThread(gateway, oldAcceptor);

                stopThread.start();
                awaitStoppingState(gateway, stopThread);
                restartThread.start();

                // [修改] 旧 stop 仍在等待旧 Acceptor 时，新一代 prepare/start 不能进入。
                assertFalse("新一代 TLS Gateway 在旧 stop 完成前启动",
                        restarted.await(250, TimeUnit.MILLISECONDS));

                releaseOldAcceptor.countDown();
                stopThread.join(2_000L);
                assertFalse("旧 stop 线程未退出", stopThread.isAlive());
                assertTrue("新一代 TLS Gateway 未启动",
                        restarted.await(2, TimeUnit.SECONDS));
                assertNull(restartFailure.get());
                assertRoundTrip(gateway, "control", new byte[]{0x31});
            } finally {
                releaseOldAcceptor.countDown();
                stopThread.join(3_500L);
                restartThread.join(3_500L);
                gateway.stop();
            }
        }
    }

    private static EmbeddedTlsGateway gateway(
            List<TlsGatewayEndpoint> endpoints,
            int maxConnections) throws Exception {
        return gateway(endpoints, maxConnections, TEST_TIMEOUT_MILLIS);
    }

    private static EmbeddedTlsGateway gateway(
            List<TlsGatewayEndpoint> endpoints,
            int maxConnections,
            int idleTimeoutMillis) throws Exception {
        return gateway(endpoints, maxConnections, idleTimeoutMillis, exception -> {
        });
    }

    private static EmbeddedTlsGateway gateway(
            List<TlsGatewayEndpoint> endpoints,
            int maxConnections,
            int idleTimeoutMillis,
            Consumer<Throwable> fatalFailureHandler) throws Exception {
        return new EmbeddedTlsGateway(
                InetAddress.getLoopbackAddress(),
                endpoints,
                serverContext,
                TEST_TIMEOUT_MILLIS,
                TEST_TIMEOUT_MILLIS,
                idleTimeoutMillis,
                maxConnections,
                TEST_BUFFER_SIZE,
                fatalFailureHandler);
    }

    private static void closeListenerUnexpectedly(
            EmbeddedTlsGateway gateway,
            String endpointName) throws Exception {
        Field listenersField = EmbeddedTlsGateway.class.getDeclaredField("listeners");
        listenersField.setAccessible(true);
        Map<?, ?> listeners = (Map<?, ?>) listenersField.get(gateway);
        Object registration = listeners.get(endpointName);
        Field listenerField = registration.getClass().getDeclaredField("listener");
        listenerField.setAccessible(true);
        ((SSLServerSocket) listenerField.get(registration)).close();
    }

    private static void assertPortClosed(int port) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), port),
                    300);
            fail("TLS Gateway 监听异常后公网端口仍可连接");
        } catch (IOException expected) {
            // [修改] 生命周期失败处理必须先关闭全部公网监听，再通知进程所有者。
        }
    }

    @SuppressWarnings("unchecked")
    private static void addAcceptorThread(
            EmbeddedTlsGateway gateway,
            Thread acceptor) throws Exception {
        Field acceptorsField = EmbeddedTlsGateway.class.getDeclaredField("acceptorThreads");
        acceptorsField.setAccessible(true);
        ((List<Thread>) acceptorsField.get(gateway)).add(acceptor);
    }

    private static void awaitStoppingState(
            EmbeddedTlsGateway gateway,
            Thread stopThread) throws Exception {
        Field preparedField = EmbeddedTlsGateway.class.getDeclaredField("prepared");
        preparedField.setAccessible(true);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while ((Boolean) preparedField.get(gateway) && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertFalse("旧 stop 未进入清理阶段", (Boolean) preparedField.get(gateway));
        assertTrue("旧 stop 没有被旧 Acceptor 阻塞", stopThread.isAlive());
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static TlsGatewayEndpoint endpoint(String name, int backendPort) {
        return new TlsGatewayEndpoint(name, 0, "127.0.0.1", backendPort);
    }

    private static void assertRoundTrip(
            EmbeddedTlsGateway gateway,
            String endpointName,
            byte[] request) throws Exception {
        assertArrayEquals(
                request,
                roundTripFragmented(gateway.getBoundPort(endpointName), request, request.length));
    }

    private static byte[] roundTripFragmented(
            int publicPort,
            byte[] request,
            int fragmentSize) throws Exception {
        try (SSLSocket client = connect(publicPort)) {
            int offset = 0;
            while (offset < request.length) {
                int length = Math.min(fragmentSize, request.length - offset);
                client.getOutputStream().write(request, offset, length);
                client.getOutputStream().flush();
                offset += length;
            }
            byte[] response = new byte[request.length];
            new DataInputStream(client.getInputStream()).readFully(response);
            return response;
        }
    }

    private static SSLSocket connect(int publicPort) throws Exception {
        SSLSocket client = newClient(publicPort);
        client.startHandshake();
        return client;
    }

    private static SSLSocket newClient(int publicPort) throws IOException {
        SSLSocket client = (SSLSocket) clientContext.getSocketFactory()
                .createSocket("127.0.0.1", publicPort);
        client.setEnabledProtocols(new String[]{"TLSv1.2"});
        client.setSoTimeout(TEST_TIMEOUT_MILLIS);
        return client;
    }

    private static void assertSocketRoundTrip(SSLSocket socket, byte[] request) throws Exception {
        socket.getOutputStream().write(request);
        socket.getOutputStream().flush();
        byte[] response = new byte[request.length];
        new DataInputStream(socket.getInputStream()).readFully(response);
        assertArrayEquals(request, response);
    }

    private static void assertPeerClosed(SSLSocket socket) throws IOException {
        socket.setSoTimeout(TEST_TIMEOUT_MILLIS);
        try {
            int value = socket.getInputStream().read();
            assertEquals(-1, value);
        } catch (SocketTimeoutException exception) {
            fail("gateway stop 后客户端连接仍未关闭");
        } catch (IOException expected) {
            assertFalse(expected instanceof SocketTimeoutException);
        }
    }

    private static void assertNoGatewayThreadsRemain() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (hasGatewayThreads() && System.nanoTime() < deadline) {
            Thread.sleep(20L);
        }
        assertFalse("TLS Gateway 线程未退出", hasGatewayThreads());
    }

    private static boolean hasGatewayThreads() {
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && thread.getName().startsWith("net-server-tls-")) {
                return true;
            }
        }
        return false;
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            return socket.getLocalPort();
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // [修改] 测试清理阶段不覆盖原始断言结果。
        }
    }

    private static final class EchoBackend implements AutoCloseable {
        private final ServerSocket listener;
        private final ExecutorService clients = Executors.newCachedThreadPool();
        private final List<Socket> activeClients = new CopyOnWriteArrayList<>();
        private final Thread acceptor;
        private volatile boolean running = true;

        private EchoBackend() throws IOException {
            listener = new ServerSocket();
            listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            acceptor = new Thread(this::acceptLoop, "test-tls-echo-acceptor-" + listener.getLocalPort());
            acceptor.setDaemon(true);
            acceptor.start();
        }

        private int getPort() {
            return listener.getLocalPort();
        }

        private void acceptLoop() {
            while (running) {
                try {
                    Socket client = listener.accept();
                    activeClients.add(client);
                    clients.execute(() -> echo(client));
                } catch (IOException exception) {
                    if (running) {
                        throw new AssertionError("测试回环后端 accept 失败", exception);
                    }
                }
            }
        }

        private void echo(Socket client) {
            byte[] buffer = new byte[1_024];
            try {
                int length;
                while ((length = client.getInputStream().read(buffer)) >= 0) {
                    if (length == 0) {
                        continue;
                    }
                    client.getOutputStream().write(buffer, 0, length);
                    client.getOutputStream().flush();
                }
            } catch (IOException ignored) {
                // [修改] Gateway 主动关闭连接属于测试预期，不在后台线程抛错。
            } finally {
                activeClients.remove(client);
                closeQuietly(client);
            }
        }

        @Override
        public void close() throws Exception {
            running = false;
            listener.close();
            for (Socket client : new ArrayList<>(activeClients)) {
                closeQuietly(client);
            }
            clients.shutdownNow();
            acceptor.join(TEST_TIMEOUT_MILLIS);
            assertFalse("测试回环后端线程未退出", acceptor.isAlive());
        }
    }

    private static final class StreamingBackend implements AutoCloseable {
        private final ServerSocket listener;
        private final Thread worker;
        private volatile Socket client;

        private StreamingBackend(byte[] payload, long intervalMillis) throws IOException {
            listener = new ServerSocket();
            listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            worker = new Thread(
                    () -> stream(payload, intervalMillis),
                    "test-tls-streaming-backend-" + listener.getLocalPort());
            worker.setDaemon(true);
            worker.start();
        }

        private int getPort() {
            return listener.getLocalPort();
        }

        private void stream(byte[] payload, long intervalMillis) {
            try {
                client = listener.accept();
                for (byte value : payload) {
                    client.getOutputStream().write(value);
                    client.getOutputStream().flush();
                    Thread.sleep(intervalMillis);
                }
            } catch (IOException exception) {
                if (!listener.isClosed()) {
                    throw new AssertionError("测试流式后端写入失败", exception);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                closeQuietly(client);
            }
        }

        @Override
        public void close() throws Exception {
            listener.close();
            closeQuietly(client);
            worker.interrupt();
            worker.join(TEST_TIMEOUT_MILLIS);
            assertFalse("测试流式后端线程未退出", worker.isAlive());
        }
    }
}
