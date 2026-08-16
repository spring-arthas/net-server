package com.alibaba.server.nio.tls;

import org.junit.Test;

import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

// [修改] Gateway 只能复制字节，不能理解或修改 FA CE 帧。
public class TlsSocketRelayTest {

    @Test
    public void copiesLargeFragmentedPayloadExactly() throws Exception {
        byte[] payload = new byte[196_733];
        new Random(47L).nextBytes(payload);
        payload[0] = (byte) 0xFA;
        payload[1] = (byte) 0xCE;
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        long copied = TlsSocketRelay.copy(
                new ByteArrayInputStream(payload),
                output,
                65_536);

        assertEquals(payload.length, copied);
        assertArrayEquals(payload, output.toByteArray());
    }

    @Test
    public void copiesEmptyStream() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        long copied = TlsSocketRelay.copy(
                new ByteArrayInputStream(new byte[0]),
                output,
                4_096);

        assertEquals(0, copied);
        assertEquals(0, output.size());
    }

    @Test
    public void rejectsInvalidBufferSize() {
        try {
            TlsSocketRelay.copy(
                    new ByteArrayInputStream(new byte[0]),
                    new ByteArrayOutputStream(),
                    0);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException exception) {
            assertEquals("bufferSize 必须大于 0", exception.getMessage());
        } catch (IOException exception) {
            fail(exception.getMessage());
        }
    }

    @Test
    public void closesBackendCreatedAfterConcurrentStop() throws Exception {
        StubSslSocket client = new StubSslSocket();
        TrackingSocket backend = new TrackingSocket();
        BlockingSocketSupplier backendSupplier = new BlockingSocketSupplier(backend);
        TlsSocketRelay.Connection connection = TlsSocketRelay.connection(
                client,
                new TlsGatewayEndpoint("control", 10086, "127.0.0.1", 10086),
                1_000,
                1_000,
                1_000,
                4_096,
                backendSupplier);

        Thread relayThread = new Thread(
                () -> connection.relay(Runnable::run),
                "test-tls-relay-stop-race");
        relayThread.start();
        assertTrue(backendSupplier.awaitCreationAttempt());

        connection.close();
        backendSupplier.release();
        relayThread.join(2_000L);

        assertFalse("转发线程未退出", relayThread.isAlive());
        assertTrue("停止后才创建的后端 Socket 没有关闭", backend.closed);
    }

    private static final class BlockingSocketSupplier implements Supplier<Socket> {
        private final Socket socket;
        private final CountDownLatch creationAttempted = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        private BlockingSocketSupplier(Socket socket) {
            this.socket = socket;
        }

        @Override
        public Socket get() {
            creationAttempted.countDown();
            try {
                if (!released.await(2, TimeUnit.SECONDS)) {
                    throw new AssertionError("等待释放后端 Socket 创建超时");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("等待释放后端 Socket 创建时被中断", exception);
            }
            return socket;
        }

        private boolean awaitCreationAttempt() throws InterruptedException {
            return creationAttempted.await(2, TimeUnit.SECONDS);
        }

        private void release() {
            released.countDown();
        }
    }

    private static final class TrackingSocket extends Socket {
        private volatile boolean closed;

        @Override
        public void setTcpNoDelay(boolean on) {
        }

        @Override
        public void setKeepAlive(boolean on) {
        }

        @Override
        public void connect(SocketAddress endpoint, int timeout) {
        }

        @Override
        public void setSoTimeout(int timeout) {
        }

        @Override
        public synchronized void close() {
            closed = true;
        }
    }

    private static final class StubSslSocket extends SSLSocket {
        @Override
        public String[] getSupportedCipherSuites() {
            return new String[0];
        }

        @Override
        public String[] getEnabledCipherSuites() {
            return new String[0];
        }

        @Override
        public void setEnabledCipherSuites(String[] suites) {
        }

        @Override
        public String[] getSupportedProtocols() {
            return new String[]{"TLSv1.2"};
        }

        @Override
        public String[] getEnabledProtocols() {
            return new String[]{"TLSv1.2"};
        }

        @Override
        public void setEnabledProtocols(String[] protocols) {
        }

        @Override
        public SSLSession getSession() {
            return null;
        }

        @Override
        public void addHandshakeCompletedListener(HandshakeCompletedListener listener) {
        }

        @Override
        public void removeHandshakeCompletedListener(HandshakeCompletedListener listener) {
        }

        @Override
        public void startHandshake() {
        }

        @Override
        public void setUseClientMode(boolean mode) {
        }

        @Override
        public boolean getUseClientMode() {
            return false;
        }

        @Override
        public void setNeedClientAuth(boolean need) {
        }

        @Override
        public boolean getNeedClientAuth() {
            return false;
        }

        @Override
        public void setWantClientAuth(boolean want) {
        }

        @Override
        public boolean getWantClientAuth() {
            return false;
        }

        @Override
        public void setEnableSessionCreation(boolean flag) {
        }

        @Override
        public boolean getEnableSessionCreation() {
            return true;
        }

        @Override
        public void setTcpNoDelay(boolean on) {
        }

        @Override
        public void setKeepAlive(boolean on) {
        }

        @Override
        public void setSoTimeout(int timeout) {
        }

        @Override
        public synchronized void close() {
        }
    }
}
