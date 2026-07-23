package com.alibaba.server.nio.repository.chat.service.impl;

import com.alibaba.server.nio.repository.chat.mapper.UserFriendMessageDO;
import com.alibaba.server.nio.repository.chat.mapper.UserFriendMessageRepository;
import com.alibaba.server.nio.repository.chat.service.ChatHistoryPage;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UserFriendMessageServiceImplHistoryTest {

    private UserFriendMessageServiceImpl service;
    private AtomicReference<String> invokedMethod;

    @Before
    public void setUp() throws Exception {
        invokedMethod = new AtomicReference<>();
        UserFriendMessageRepository repository = (UserFriendMessageRepository) Proxy.newProxyInstance(
                UserFriendMessageRepository.class.getClassLoader(),
                new Class<?>[] { UserFriendMessageRepository.class },
                (proxy, method, args) -> {
                    invokedMethod.set(method.getName());
                    switch (method.getName()) {
                        case "getLatestChatHistory":
                            return messages(9L, 8L, 7L);
                        case "getChatHistoryBefore":
                            return messages(6L, 5L, 4L);
                        case "getChatHistoryAfter":
                            return messages(7L, 8L, 9L);
                        case "getChatHistory":
                            return messages(2L, 3L, 4L);
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });

        service = new UserFriendMessageServiceImpl();
        Field repositoryField = UserFriendMessageServiceImpl.class.getDeclaredField("chatMessageRepository");
        repositoryField.setAccessible(true);
        repositoryField.set(service, repository);
    }

    @Test
    public void latestPageOverfetchesAndReturnsAscendingDisplayOrder() {
        ChatHistoryPage page = service.getChatHistoryPage(1, 2, null, null, null, 2);

        assertEquals(Arrays.asList(8L, 9L), ids(page.getMessages()));
        assertTrue(page.isHasMore());
        assertEquals(Long.valueOf(8L), page.getNextBeforeMessageId());
        assertEquals(Long.valueOf(9L), page.getLatestMessageId());
        assertEquals("getLatestChatHistory", invokedMethod.get());
    }

    @Test
    public void beforePageUsesOldestCursorAndTracksOlderAvailability() {
        ChatHistoryPage page = service.getChatHistoryPage(1, 2, 8L, null, null, 2);

        assertEquals(Arrays.asList(5L, 6L), ids(page.getMessages()));
        assertTrue(page.isHasMore());
        assertEquals(Long.valueOf(5L), page.getNextBeforeMessageId());
        assertEquals("getChatHistoryBefore", invokedMethod.get());
    }

    @Test
    public void afterPageReturnsAscendingRows() {
        ChatHistoryPage page = service.getChatHistoryPage(1, 2, null, 6L, null, 2);

        assertEquals(Arrays.asList(7L, 8L), ids(page.getMessages()));
        assertTrue(page.isHasMore());
        assertEquals(Long.valueOf(8L), page.getLatestMessageId());
        assertEquals("getChatHistoryAfter", invokedMethod.get());
    }

    @Test
    public void legacyOffsetStillUsesLegacyQuery() {
        ChatHistoryPage page = service.getChatHistoryPage(1, 2, null, null, 20, 2);

        assertEquals(Arrays.asList(3L, 4L), ids(page.getMessages()));
        assertTrue(page.isHasMore());
        assertEquals(Long.valueOf(3L), page.getNextBeforeMessageId());
        assertEquals(Long.valueOf(4L), page.getLatestMessageId());
        assertEquals("getChatHistory", invokedMethod.get());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsBeforeAndAfterTogether() {
        service.getChatHistoryPage(1, 2, 8L, 6L, null, 20);
    }

    private static List<UserFriendMessageDO> messages(Long... ids) {
        List<UserFriendMessageDO> result = new ArrayList<>();
        for (Long id : ids) {
            UserFriendMessageDO message = new UserFriendMessageDO();
            message.setId(id);
            result.add(message);
        }
        return result;
    }

    private static List<Long> ids(List<UserFriendMessageDO> messages) {
        return messages.stream().map(UserFriendMessageDO::getId).collect(Collectors.toList());
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == byte.class || returnType == short.class || returnType == int.class
                || returnType == long.class || returnType == float.class || returnType == double.class) {
            return 0;
        }
        if (returnType == char.class) {
            return '\0';
        }
        return null;
    }
}
