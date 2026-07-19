package com.alibaba.server.nio.repository.user.service.impl;

import com.alibaba.server.nio.repository.user.mapper.UserFriendApplyRepository;
import com.alibaba.server.nio.repository.user.mapper.UserFriendsRepository;
import com.alibaba.server.nio.repository.user.mapper.UserRepository;
import com.alibaba.server.nio.repository.user.repository.dataobject.UserFriendApplyDo;
import com.alibaba.server.nio.repository.user.service.dto.UserSearchDTO;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

public class FriendshipServiceImplTest {

    @Test
    public void handleRequestRejectsInvalidActionBeforeUpdatingDatabase() {
        AtomicInteger updateCount = new AtomicInteger();
        FriendshipServiceImpl service = service(
                applyRepository((method, args) -> {
                    if ("updatePendingStatus".equals(method.getName())) {
                        updateCount.incrementAndGet();
                    }
                    return defaultValue(method.getReturnType());
                }),
                friendsRepository((method, args) -> defaultValue(method.getReturnType())),
                userRepository((method, args) -> defaultValue(method.getReturnType())));

        try {
            service.handleRequest(7, 10L, 9, null);
            Assert.fail("非法 action 应被拒绝");
        } catch (IllegalArgumentException expected) {
            Assert.assertEquals("好友申请操作无效", expected.getMessage());
        }
        Assert.assertEquals(0, updateCount.get());
    }

    @Test
    public void handleRequestCannotUpdateRequestOwnedByAnotherReceiver() {
        AtomicInteger updateCount = new AtomicInteger();
        FriendshipServiceImpl service = service(
                applyRepository((method, args) -> {
                    if ("findPendingForUpdate".equals(method.getName())) {
                        return null;
                    }
                    if ("updatePendingStatus".equals(method.getName())) {
                        updateCount.incrementAndGet();
                    }
                    return defaultValue(method.getReturnType());
                }),
                friendsRepository((method, args) -> defaultValue(method.getReturnType())),
                userRepository((method, args) -> defaultValue(method.getReturnType())));

        try {
            service.handleRequest(7, 10L, 2, null);
            Assert.fail("非接收者不得处理申请");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("无权处理"));
        }
        Assert.assertEquals(0, updateCount.get());
    }

    @Test
    public void acceptCreatesBothDirectionsAndUsesTransaction() throws Exception {
        UserFriendApplyDo apply = new UserFriendApplyDo();
        apply.setId(10L);
        apply.setSenderId(5);
        apply.setReceiverId(7);
        apply.setStatus(0);
        AtomicInteger upsertCount = new AtomicInteger();

        FriendshipServiceImpl service = service(
                applyRepository((method, args) -> {
                    if ("findPendingForUpdate".equals(method.getName())) {
                        return apply;
                    }
                    if ("updatePendingStatus".equals(method.getName())) {
                        return 1;
                    }
                    return defaultValue(method.getReturnType());
                }),
                friendsRepository((method, args) -> {
                    if ("upsertActiveFriend".equals(method.getName())) {
                        upsertCount.incrementAndGet();
                        return 1;
                    }
                    return defaultValue(method.getReturnType());
                }),
                userRepository((method, args) -> defaultValue(method.getReturnType())));

        service.handleRequest(7, 10L, 1, "备注");
        Assert.assertEquals(2, upsertCount.get());

        Method method = FriendshipServiceImpl.class.getMethod(
                "handleRequest", Integer.class, Long.class, Integer.class, String.class);
        Assert.assertNotNull(method.getAnnotation(Transactional.class));
    }

    @Test
    public void searchDtoDoesNotContainSensitiveFields() {
        for (Field field : UserSearchDTO.class.getDeclaredFields()) {
            Assert.assertFalse("password".equals(field.getName()));
            Assert.assertFalse("mail".equals(field.getName()));
            Assert.assertFalse("phone".equals(field.getName()));
        }
    }

    private FriendshipServiceImpl service(UserFriendApplyRepository applyRepository,
            UserFriendsRepository friendsRepository,
            UserRepository userRepository) {
        return new FriendshipServiceImpl(applyRepository, friendsRepository, userRepository);
    }

    private UserFriendApplyRepository applyRepository(Invocation invocation) {
        return proxy(UserFriendApplyRepository.class, invocation);
    }

    private UserFriendsRepository friendsRepository(Invocation invocation) {
        return proxy(UserFriendsRepository.class, invocation);
    }

    private UserRepository userRepository(Invocation invocation) {
        return proxy(UserRepository.class, invocation);
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type },
                (proxy, method, args) -> invocation.invoke(method, args));
    }

    private static Object defaultValue(Class<?> type) {
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == boolean.class) {
            return false;
        }
        return null;
    }

    private interface Invocation {
        Object invoke(Method method, Object[] args) throws Throwable;
    }
}
