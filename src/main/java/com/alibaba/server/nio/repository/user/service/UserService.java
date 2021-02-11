package com.alibaba.server.nio.repository.user.service;

import com.alibaba.server.nio.repository.user.service.dto.UserDTO;
import com.alibaba.server.nio.repository.user.service.param.UserCreateParam;
import com.alibaba.server.nio.repository.user.service.param.UserQueryParam;
import com.alibaba.server.nio.repository.user.service.param.UserUpdateParam;

/**
 * 用户服务
 *
 * @author spring
 * */
public interface UserService {

    /**
     * 根据用户名加载用户
     *
     * @param userQueryParam
     * @return userDto
     */
    UserDTO getUserByName(UserQueryParam userQueryParam);

    /**
     * 创建用户
     *
     * @param userCreateParam
     * @return userDto
     */
    UserDTO create(UserCreateParam userCreateParam);

    /**
     * 更新用户
     *
     * @param userUpdateParam
     * @return userDto
     */
    void update(UserUpdateParam userUpdateParam);

    /**
     * 获取用户在线情况,内存获取
     *
     * @param param
     * @return userDto
     */
    UserDTO getOnlineUser(UserQueryParam param);
}
