package com.alibaba.server.nio.repository.dynamic.mapper;

import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Select;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class UserDynamicMediaAccessRepositoryTest {

    @Test
    public void accessibleMediaMustStillExist() throws Exception {
        Method method = UserDynamicMediaAccessRepository.class.getMethod(
                "selectAccessibleFileIds", Long.class, java.util.List.class);
        Select select = method.getAnnotation(Select.class);
        String sql = String.join(" ", select.value()).replaceAll("\\s+", " ");

        assertTrue(sql.contains("f.del = 'N'"));
        assertTrue(sql.contains("f.is_file = 'Y'"));
        assertTrue(sql.contains("f.is_exist = 'Y'"));
    }
}
