package com.alibaba.server.nio.repository.user.service.impl;

import com.alibaba.server.nio.repository.user.repository.dataobject.UserDo;
import com.alibaba.server.nio.repository.user.service.param.UserUpdateParam;
import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;

public class UserServiceImplTest {

    @Test
    public void updateParamToDoCopiesAvatar() throws Exception {
        UserUpdateParam param = new UserUpdateParam();
        param.setId(7L);
        param.setAvatar("/tmp/avatar.jpg");

        Method method = UserServiceImpl.class.getDeclaredMethod("updateParamToDo", UserUpdateParam.class);
        method.setAccessible(true);

        UserDo userDo = (UserDo) method.invoke(new UserServiceImpl(), param);

        assertEquals("/tmp/avatar.jpg", userDo.getAvatar());
    }
}
