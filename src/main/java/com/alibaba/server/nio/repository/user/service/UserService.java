package com.alibaba.server.nio.repository.user.service;

import com.alibaba.server.nio.repository.user.service.dto.UserDTO;
import com.alibaba.server.nio.repository.user.service.dto.UserSearchDTO;
import com.alibaba.server.nio.repository.user.service.param.UserCreateParam;
import com.alibaba.server.nio.repository.user.service.param.UserQueryParam;
import com.alibaba.server.nio.repository.user.service.param.UserUpdateParam;

import java.util.List;
import java.util.Map;

/**
 * 用户服务
 *
 * @author spring
 */
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

    // ========== 用户认证接口 ==========

    /**
     * 用户注册
     * 
     * @param userName 用户名（≤5字符）
     * @param password 密码
     * @return 注册成功的用户信息
     * @throws IllegalArgumentException 用户名过长或已存在
     */
    UserDTO register(String userName, String password, String mail, String nickName, String avatar);

    /**
     * 用户登录
     * 
     * @param userName 用户名
     * @param password 密码
     * @return 登录成功的用户信息
     * @throws IllegalArgumentException 用户名不存在或密码错误
     */
    UserDTO login(String userName, String password);

    /**
     * 修改密码
     * 
     * @param userId      用户ID
     * @param oldPassword 旧密码
     * @param newPassword 新密码
     * @throws IllegalArgumentException 旧密码错误
     */
    void changePassword(Long userId, String oldPassword, String newPassword);

    List<UserDTO> getUserListByName(UserQueryParam userQueryParam);

    UserDTO getById(Long id);

    /**
     * 根据 ID 列表批量查询用户
     *
     * @param ids 用户 ID 列表
     * @return Map，key 为用户 ID，value 为用户信息
     */
    Map<Long, UserDTO> listByIds(List<Long> ids);

    /** 好友搜索，只返回公开字段。 */
    List<UserSearchDTO> searchUsers(String keyword);

    boolean existsActiveUser(Long userId);
}
