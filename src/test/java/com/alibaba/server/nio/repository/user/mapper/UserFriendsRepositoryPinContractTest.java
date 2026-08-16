package com.alibaba.server.nio.repository.user.mapper;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.Assert.assertTrue;

public class UserFriendsRepositoryPinContractTest {

    @Test
    public void ownedPinUpdateIsScopedAndIdempotent() throws Exception {
        Method method = UserFriendsRepository.class.getMethod(
                "updateOwnedPin", Long.class, Integer.class, boolean.class);
        String sql = String.join(" ", Arrays.asList(method.getAnnotation(Update.class).value()));

        assertTrue(sql.contains("is_pinned = #{pinned}"));
        assertTrue(sql.contains("COALESCE(pinned_at, NOW(3))"));
        assertTrue(sql.contains("ELSE NULL"));
        assertTrue(sql.contains("id = #{id}"));
        assertTrue(sql.contains("user_id = #{userId}"));
        assertTrue(sql.contains("del = 'N'"));
    }

    @Test
    public void canonicalReadUsesTheSameOwnedActiveScope() throws Exception {
        Method method = UserFriendsRepository.class.getMethod(
                "findOwnedActiveById", Long.class, Integer.class);
        String sql = String.join(" ", Arrays.asList(method.getAnnotation(Select.class).value()));

        assertTrue(sql.contains("is_pinned"));
        assertTrue(sql.contains("pinned_at"));
        assertTrue(sql.contains("id = #{id}"));
        assertTrue(sql.contains("user_id = #{userId}"));
        assertTrue(sql.contains("del = 'N'"));
    }
}
