package com.alibaba.server.nio.repository.user.service;

import com.alibaba.server.nio.repository.user.service.dto.UserFriendApplyDTO;
import com.alibaba.server.nio.repository.user.service.param.UserFriendApplyCreateParam;
import com.alibaba.server.nio.repository.user.service.param.UserFriendApplyQueryParam;
import com.alibaba.server.nio.repository.user.service.param.UserFriendApplyUpdateParam;
import java.util.List;

/**
 * 好友添加请求服务
 * 
 * @author spring
 */
public interface UserFriendApplyService {

    /**
     * 创建好友添加请求
     * 
     * @param param
     * @return
     */
    UserFriendApplyDTO create(UserFriendApplyCreateParam param);

    /**
     * 更新好友添加请求
     * 
     * @param param
     */
    void update(UserFriendApplyUpdateParam param);

    /**
     * 查询好友添加请求列表
     * 
     * @param param
     * @return
     */
    List<UserFriendApplyDTO> query(UserFriendApplyQueryParam param);
}
