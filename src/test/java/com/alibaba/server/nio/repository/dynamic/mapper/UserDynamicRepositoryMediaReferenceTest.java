package com.alibaba.server.nio.repository.dynamic.mapper;

import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Select;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class UserDynamicRepositoryMediaReferenceTest {

    @Test
    public void visibleDynamicMediaReferenceUsesFriendVisibilityAndJsonFileId() throws Exception {
        Method method = UserDynamicRepository.class.getMethod(
                "countVisibleMediaReferences", Long.class, Long.class);
        Select select = method.getAnnotation(Select.class);
        String sql = String.join(" ", select.value()).replaceAll("\\s+", " ");

        assertTrue(sql.contains("d.del = 'N'"));
        assertTrue(sql.contains("d.user_id = #{userId}"));
        assertTrue(sql.contains("user_friends"));
        assertTrue(sql.contains("d.media_json"));
        assertTrue(sql.contains("JSON_CONTAINS"));
        assertTrue(sql.contains("$.media[*].fileId"));
        assertTrue(sql.contains("FIND_IN_SET"));
        assertTrue(sql.contains("#{fileId}"));
    }
}
