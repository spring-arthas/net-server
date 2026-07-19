package com.alibaba.server.nio.repository.user.service;

import com.alibaba.server.nio.repository.user.service.dto.FriendRequestHandleResult;
import com.alibaba.server.nio.repository.user.service.dto.UserFriendApplyDTO;

/**
 * 好友关系领域服务。
 *
 * 负责好友申请、审批和好友关系写入的权限校验、事务和幂等性。
 */
public interface FriendshipService {

    UserFriendApplyDTO sendRequest(Integer senderId, Integer receiverId, String requestMessage);

    FriendRequestHandleResult handleRequest(Integer receiverId, Long requestId, Integer action, String alias);

    boolean isActiveFriend(Integer userId, Integer friendId);

    void updateAlias(Integer userId, Long friendshipId, String alias);
}
