package com.alibaba.server.nio.service.file.handler;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.server.nio.core.server.BasicServer;
import com.alibaba.server.nio.model.SocketChannelContext;
import com.alibaba.server.nio.model.file.FileUploadFrame;
import com.alibaba.server.nio.repository.file.service.FileService;
import com.alibaba.server.nio.repository.file.service.FileTaskService;
import com.alibaba.server.nio.repository.user.service.FriendshipService;
import com.alibaba.server.nio.repository.user.service.UserService;
import com.alibaba.server.nio.repository.user.service.dto.FriendPinUpdateResult;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;

public class TextTransmissionHandlerFriendPinIntegrationTest {

    private ClassPathXmlApplicationContext originalContext;
    private ClassPathXmlApplicationContext testContext;

    @Before
    public void setUp() {
        originalContext = BasicServer.classPathXmlApplicationContext;
    }

    @After
    public void tearDown() {
        BasicServer.classPathXmlApplicationContext = originalContext;
        if (testContext != null) {
            testContext.close();
        }
    }

    @Test
    public void dispatchUsesAuthenticatedUserAndWritesCanonicalResponseFrame() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        installFriendshipService((proxy, method, args) -> {
            if ("updatePinned".equals(method.getName())) {
                calls.incrementAndGet();
                Assert.assertEquals(Integer.valueOf(7), args[0]);
                Assert.assertEquals(Long.valueOf(12), args[1]);
                Assert.assertEquals(Boolean.TRUE, args[2]);
                return new FriendPinUpdateResult(12L, true, new Date(1785360000123L));
            }
            return defaultValue(method.getReturnType());
        });

        JSONObject response = invokeAndRead(
                "{\"relationshipId\":12,\"pinned\":true,\"userId\":999}", true);

        Assert.assertEquals(1, calls.get());
        Assert.assertTrue(response.getBooleanValue("success"));
        JSONObject data = response.getJSONObject("data");
        Assert.assertEquals(Long.valueOf(12), data.getLong("relationshipId"));
        Assert.assertTrue(data.getBooleanValue("pinned"));
        Assert.assertEquals(Long.valueOf(1785360000123L), data.getLong("pinnedAt"));
    }

    @Test
    public void unauthenticatedRequestWritesNotLoggedInResponseWithoutCallingService() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        installFriendshipService((proxy, method, args) -> {
            if ("updatePinned".equals(method.getName())) {
                calls.incrementAndGet();
            }
            return defaultValue(method.getReturnType());
        });

        JSONObject response = invokeAndRead("{\"relationshipId\":12,\"pinned\":true}", false);

        Assert.assertEquals(0, calls.get());
        Assert.assertFalse(response.getBooleanValue("success"));
        Assert.assertEquals("NOT_LOGGED_IN", response.getString("errorCode"));
    }

    private JSONObject invokeAndRead(String payload, boolean authenticated) throws Exception {
        try (ServerSocketChannel listener = ServerSocketChannel.open();
                SocketChannel client = SocketChannel.open()) {
            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            client.connect(listener.getLocalAddress());
            try (SocketChannel server = listener.accept()) {
                SocketChannelContext channelContext = new SocketChannelContext();
                channelContext.setSocketChannel(server);
                channelContext.setRemoteAddress("127.0.0.1:test");
                channelContext.setHandlerType("TEXT");
                if (authenticated) {
                    channelContext.putAttribute("loggedInUserId", 7L);
                }

                byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
                FileUploadFrame frame = new FileUploadFrame();
                frame.setType(FileUploadFrame.FrameType.USER_FRIEND_PIN_UPDATE_REQ);
                frame.setData(bytes);
                frame.setDataLength(bytes.length);

                Method processFrame = TextTransmissionHandler.class.getDeclaredMethod(
                        "processFrame", FileUploadFrame.class, SocketChannelContext.class);
                processFrame.setAccessible(true);
                processFrame.invoke(new TextTransmissionHandler(), frame, channelContext);

                ByteBuffer header = ByteBuffer.allocate(FileUploadFrame.HEADER_LENGTH);
                readFully(client, header);
                // [修改] 强制使用 Java 8 的 Buffer.flip() 签名，避免 JDK 17 编译后在 Java 8 运行时报错。
                ((Buffer) header).flip();
                Assert.assertEquals(FileUploadFrame.MAGIC[0], header.get());
                Assert.assertEquals(FileUploadFrame.MAGIC[1], header.get());
                Assert.assertEquals(FileUploadFrame.FrameType.USER_FRIEND_PIN_UPDATE_RESPONSE.getCode(),
                        header.get() & 0xFF);
                header.get();
                int length = header.getInt();
                ByteBuffer body = ByteBuffer.allocate(length);
                readFully(client, body);
                return JSON.parseObject(new String(body.array(), StandardCharsets.UTF_8));
            }
        }
    }

    private void readFully(SocketChannel channel, ByteBuffer buffer) throws Exception {
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer);
            if (read < 0) {
                throw new IllegalStateException("响应帧提前结束");
            }
        }
    }

    private void installFriendshipService(java.lang.reflect.InvocationHandler handler) {
        FriendshipService service = (FriendshipService) Proxy.newProxyInstance(
                FriendshipService.class.getClassLoader(), new Class<?>[] { FriendshipService.class }, handler);
        testContext = new ClassPathXmlApplicationContext();
        testContext.refresh();
        testContext.getBeanFactory().registerSingleton("friendshipService", service);
        testContext.getBeanFactory().registerSingleton("userService", interfaceProxy(UserService.class));
        testContext.getBeanFactory().registerSingleton("fileService", interfaceProxy(FileService.class));
        testContext.getBeanFactory().registerSingleton("fileTaskService", interfaceProxy(FileTaskService.class));
        BasicServer.classPathXmlApplicationContext = testContext;
    }

    private Object interfaceProxy(Class<?> type) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type },
                (proxy, method, args) -> defaultValue(method.getReturnType()));
    }

    private Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }
}
