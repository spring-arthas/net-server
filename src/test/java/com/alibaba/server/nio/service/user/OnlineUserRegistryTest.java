package com.alibaba.server.nio.service.user;

import com.alibaba.server.nio.model.SocketChannelContext;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ConcurrentHashMap;

public class OnlineUserRegistryTest {

    @After
    public void tearDown() {
        com.alibaba.server.nio.core.server.BasicServer.onlineUserChannels.clear();
    }

    @Test
    public void reportsOnlineOnlyForMatchingLoggedInUserAndConnectedChannel() throws Exception {
        ConcurrentHashMap<Long, SocketChannelContext> channels = new ConcurrentHashMap<>();
        ChannelPair pair = connectedChannelPair();
        try {
            SocketChannelContext context = new SocketChannelContext();
            context.setSocketChannel(pair.client);
            context.setRemoteAddress("127.0.0.1:50001");
            context.putAttribute("loggedInUserId", 5L);
            channels.put(5L, context);

            Assert.assertTrue(OnlineUserRegistry.isUserOnline(5L, channels));
            Assert.assertFalse(OnlineUserRegistry.isUserOnline(6L, channels));

            context.putAttribute("loggedInUserId", 7L);
            Assert.assertFalse(OnlineUserRegistry.isUserOnline(5L, channels));
        } finally {
            pair.close();
        }
    }

    @Test
    public void cleanupByRemoteAddressDoesNotRemoveReconnectedUserContext() {
        ConcurrentHashMap<Long, SocketChannelContext> channels = new ConcurrentHashMap<>();
        SocketChannelContext oldContext = context("127.0.0.1:50001", 5L);
        SocketChannelContext newContext = context("127.0.0.1:50002", 5L);
        SocketChannelContext otherUser = context("127.0.0.1:50001", 6L);

        channels.put(5L, newContext);
        channels.put(6L, otherUser);

        int removed = OnlineUserRegistry.cleanupConnection("127.0.0.1:50001", channels);

        Assert.assertEquals(1, removed);
        Assert.assertSame(newContext, channels.get(5L));
        Assert.assertFalse(channels.containsKey(6L));
        Assert.assertNotSame(oldContext, channels.get(5L));
    }

    @Test
    public void cleanupBySocketChannelRemovesAlreadyClosedConnection() throws Exception {
        ConcurrentHashMap<Long, SocketChannelContext> channels = new ConcurrentHashMap<>();
        ChannelPair pair = connectedChannelPair();
        try {
            SocketChannelContext closingContext = context("127.0.0.1:50001", 5L);
            closingContext.setSocketChannel(pair.client);
            SocketChannelContext otherContext = context("127.0.0.1:50002", 6L);
            otherContext.setSocketChannel(pair.accepted);
            channels.put(5L, closingContext);
            channels.put(6L, otherContext);

            pair.client.close();

            int removed = OnlineUserRegistry.cleanupSocketChannel(pair.client, channels);

            Assert.assertEquals(1, removed);
            Assert.assertFalse(channels.containsKey(5L));
            Assert.assertSame(otherContext, channels.get(6L));
        } finally {
            pair.close();
        }
    }

    private static SocketChannelContext context(String remoteAddress, Long userId) {
        SocketChannelContext context = new SocketChannelContext();
        context.setRemoteAddress(remoteAddress);
        context.putAttribute("loggedInUserId", userId);
        return context;
    }

    private static ChannelPair connectedChannelPair() throws IOException {
        ServerSocketChannel server = ServerSocketChannel.open();
        server.bind(new InetSocketAddress("127.0.0.1", 0));
        SocketChannel client = SocketChannel.open();
        client.connect(server.getLocalAddress());
        SocketChannel accepted = server.accept();
        return new ChannelPair(server, client, accepted);
    }

    private static final class ChannelPair {
        private final ServerSocketChannel server;
        private final SocketChannel client;
        private final SocketChannel accepted;

        private ChannelPair(ServerSocketChannel server, SocketChannel client, SocketChannel accepted) {
            this.server = server;
            this.client = client;
            this.accepted = accepted;
        }

        private void close() throws IOException {
            client.close();
            accepted.close();
            server.close();
        }
    }
}
