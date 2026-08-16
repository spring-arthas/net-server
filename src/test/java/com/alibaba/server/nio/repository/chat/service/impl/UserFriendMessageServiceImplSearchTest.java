package com.alibaba.server.nio.repository.chat.service.impl;

import com.alibaba.server.nio.repository.chat.mapper.UserFriendMessageDO;
import com.alibaba.server.nio.repository.chat.mapper.UserFriendMessageRepository;
import com.alibaba.server.nio.repository.chat.service.ChatMessageSearchPage;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UserFriendMessageServiceImplSearchTest {

    private UserFriendMessageServiceImpl service;
    private AtomicReference<String> invokedMethod;
    private AtomicReference<Object[]> invokedArguments;

    @Before
    public void setUp() throws Exception {
        invokedMethod = new AtomicReference<String>();
        invokedArguments = new AtomicReference<Object[]>();
        UserFriendMessageRepository repository = (UserFriendMessageRepository) Proxy.newProxyInstance(
                UserFriendMessageRepository.class.getClassLoader(),
                new Class<?>[] { UserFriendMessageRepository.class },
                (proxy, method, args) -> {
                    invokedMethod.set(method.getName());
                    invokedArguments.set(args);
                    if (List.class.isAssignableFrom(method.getReturnType())) {
                        return Collections.<UserFriendMessageDO>emptyList();
                    }
                    return defaultValue(method.getReturnType());
                });

        service = new UserFriendMessageServiceImpl();
        Field repositoryField = UserFriendMessageServiceImpl.class.getDeclaredField("chatMessageRepository");
        repositoryField.setAccessible(true);
        repositoryField.set(service, repository);
    }

    @Test
    public void friendIdRoutesSearchToConversationQuery() {
        service.searchMessages(7, 9, "  hello  ", 500);

        assertEquals("searchMessagesInConversation", invokedMethod.get());
        assertEquals(7, invokedArguments.get()[0]);
        assertEquals(9, invokedArguments.get()[1]);
        assertEquals("hello", invokedArguments.get()[2]);
        assertEquals(100, invokedArguments.get()[3]);
    }

    @Test
    public void missingFriendIdKeepsLegacyGlobalSearch() {
        service.searchMessages(7, null, "  hello  ", 0);

        assertEquals("searchMessages", invokedMethod.get());
        assertEquals(7, invokedArguments.get()[0]);
        assertEquals("hello", invokedArguments.get()[1]);
        assertEquals(50, invokedArguments.get()[2]);
    }

    @Test
    public void pageSearchOverFetchesOneAndReturnsCanonicalHasMore() throws Exception {
        List<UserFriendMessageDO> rows = new java.util.ArrayList<UserFriendMessageDO>();
        for (long id = 1; id <= 101; id++) {
            UserFriendMessageDO message = new UserFriendMessageDO();
            message.setId(id);
            rows.add(message);
        }
        injectRepositoryReturning(rows);

        ChatMessageSearchPage page = service.searchMessagesPage(7, 9, "hello", 500);

        assertEquals(101, invokedArguments.get()[3]);
        assertEquals(100, page.getMessages().size());
        assertTrue(page.isHasMore());
    }

    @Test
    public void pageSearchDoesNotReportHasMoreWhenExactlyOnePageExists() throws Exception {
        List<UserFriendMessageDO> rows = new java.util.ArrayList<UserFriendMessageDO>();
        for (long id = 1; id <= 50; id++) {
            UserFriendMessageDO message = new UserFriendMessageDO();
            message.setId(id);
            rows.add(message);
        }
        injectRepositoryReturning(rows);

        ChatMessageSearchPage page = service.searchMessagesPage(7, null, "hello", 50);

        assertEquals(51, invokedArguments.get()[2]);
        assertEquals(50, page.getMessages().size());
        assertFalse(page.isHasMore());
    }

    private void injectRepositoryReturning(final List<UserFriendMessageDO> rows) throws Exception {
        UserFriendMessageRepository repository = (UserFriendMessageRepository) Proxy.newProxyInstance(
                UserFriendMessageRepository.class.getClassLoader(),
                new Class<?>[] { UserFriendMessageRepository.class },
                (proxy, method, args) -> {
                    invokedMethod.set(method.getName());
                    invokedArguments.set(args);
                    if (List.class.isAssignableFrom(method.getReturnType())) {
                        return rows;
                    }
                    return defaultValue(method.getReturnType());
                });
        Field repositoryField = UserFriendMessageServiceImpl.class.getDeclaredField("chatMessageRepository");
        repositoryField.setAccessible(true);
        repositoryField.set(service, repository);
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) return null;
        if (returnType == boolean.class) return false;
        if (returnType == char.class) return '\0';
        return 0;
    }
}
