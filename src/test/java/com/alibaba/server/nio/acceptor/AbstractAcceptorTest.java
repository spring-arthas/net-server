package com.alibaba.server.nio.acceptor;

import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.spi.LoggingEvent;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AbstractAcceptorTest {

    @Test
    public void continuesAcceptingAfterOneClientInitializationFails() throws IOException {
        List<SocketChannel> acceptedChannels = new ArrayList<>();
        try (ServerSocketChannel serverSocketChannel = ServerSocketChannel.open()) {
            serverSocketChannel.bind(new InetSocketAddress("127.0.0.1", 0));
            serverSocketChannel.configureBlocking(false);
            InetSocketAddress serverAddress = (InetSocketAddress) serverSocketChannel.getLocalAddress();

            try (SocketChannel firstClient = SocketChannel.open(serverAddress);
                    SocketChannel secondClient = SocketChannel.open(serverAddress)) {
                AtomicInteger registrationAttempts = new AtomicInteger();

                int acceptedCount = new TestAcceptor().acceptPendingForTest(
                        serverSocketChannel,
                        registrationAttempts,
                        acceptedChannels);

                assertEquals(2, registrationAttempts.get());
                assertEquals(1, acceptedCount);
                assertFalse(acceptedChannels.get(0).isOpen());
                assertTrue(acceptedChannels.get(1).isOpen());
            }
        } finally {
            for (SocketChannel acceptedChannel : acceptedChannels) {
                if (acceptedChannel.isOpen()) {
                    acceptedChannel.close();
                }
            }
        }
    }

    @Test
    public void rateLimitsClientSocketInitializationWarnings() throws IOException {
        Logger logger = Logger.getLogger(AbstractAcceptor.class);
        Level originalLevel = logger.getLevel();
        RecordingAppender appender = new RecordingAppender();
        logger.setLevel(Level.TRACE);
        logger.addAppender(appender);

        try (ServerSocketChannel serverSocketChannel = ServerSocketChannel.open()) {
            serverSocketChannel.bind(new InetSocketAddress("127.0.0.1", 0));
            serverSocketChannel.configureBlocking(false);
            InetSocketAddress serverAddress = (InetSocketAddress) serverSocketChannel.getLocalAddress();

            try (SocketChannel firstClient = SocketChannel.open(serverAddress);
                    SocketChannel secondClient = SocketChannel.open(serverAddress)) {
                new TestAcceptor().acceptPendingConnections(
                        serverSocketChannel,
                        socketChannel -> {
                            throw new SocketException("Invalid argument");
                        },
                        "日志限频-" + System.nanoTime());
            }
        } finally {
            logger.removeAppender(appender);
            logger.setLevel(originalLevel);
        }

        assertEquals(2, appender.levels.size());
        assertEquals(Level.WARN, appender.levels.get(0));
        assertEquals(Level.TRACE, appender.levels.get(1));
    }

    @Test
    public void configuresAcceptedSocketForLowLatencyNonBlockingIo() throws IOException {
        // [修改] 非阻塞 NIO 不使用 SO_TIMEOUT，关闭也不能被 SO_LINGER 阻塞 20 秒。
        try (ServerSocketChannel serverSocketChannel = ServerSocketChannel.open()) {
            serverSocketChannel.bind(new InetSocketAddress("127.0.0.1", 0));
            InetSocketAddress serverAddress = (InetSocketAddress) serverSocketChannel.getLocalAddress();
            try (SocketChannel client = SocketChannel.open(serverAddress);
                    SocketChannel accepted = serverSocketChannel.accept()) {
                new TestAcceptor().configureAcceptedSocketForTest(accepted);

                assertFalse(accepted.isBlocking());
                assertTrue(accepted.socket().getKeepAlive());
                assertTrue(accepted.socket().getTcpNoDelay());
                assertEquals(-1, accepted.socket().getSoLinger());
                assertEquals(0, accepted.socket().getSoTimeout());
            }
        }
    }

    private static final class TestAcceptor extends AbstractAcceptor {

        private void configureAcceptedSocketForTest(SocketChannel socketChannel) throws IOException {
            configureAcceptedSocket(socketChannel);
        }

        private int acceptPendingForTest(
                ServerSocketChannel serverSocketChannel,
                AtomicInteger registrationAttempts,
                List<SocketChannel> acceptedChannels) throws IOException {
            return acceptPendingConnections(serverSocketChannel, socketChannel -> {
                acceptedChannels.add(socketChannel);
                if (registrationAttempts.incrementAndGet() == 1) {
                    throw new SocketException("Invalid argument");
                }
            }, "测试");
        }
    }

    private static final class RecordingAppender extends AppenderSkeleton {
        private final List<Level> levels = new ArrayList<>();

        @Override
        protected void append(LoggingEvent event) {
            levels.add(event.getLevel());
        }

        @Override
        public void close() {
        }

        @Override
        public boolean requiresLayout() {
            return false;
        }
    }
}
