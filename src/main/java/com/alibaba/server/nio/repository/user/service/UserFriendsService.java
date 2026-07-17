package com.alibaba.server.nio.repository.user.service;

import com.alibaba.server.nio.repository.user.service.dto.UserFriendsDTO;
import com.alibaba.server.nio.repository.user.service.param.UserFriendsCreateParam;
import com.alibaba.server.nio.repository.user.service.param.UserFriendsQueryParam;
import com.alibaba.server.nio.repository.user.service.param.UserFriendsUpdateParam;
import java.util.List;

/**
 * 用户好友关系服务
 * 
 * @author spring
 */
public interface UserFriendsService {

    /**
     * 创建用户好友关系
     * 
     * @param param
     * @return
     */
    UserFriendsDTO create(UserFriendsCreateParam param);

    /**
     * 更新用户好友关系
     * 
     * @param param
     */
    void update(UserFriendsUpdateParam param);

    /**
     * 查询用户好友关系列表
     * 
     * @param param
     * @return
     */
    List<UserFriendsDTO> query(UserFriendsQueryParam param);

    /**
     * 删除用户好友关系（逻辑删除）
     * 
     * @param id
     */
    void delete(Long id);
}
