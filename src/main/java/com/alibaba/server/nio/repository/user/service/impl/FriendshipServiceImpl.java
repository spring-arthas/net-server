package com.alibaba.server.nio.repository.user.service.impl;

import com.alibaba.server.nio.repository.user.mapper.UserFriendApplyRepository;
import com.alibaba.server.nio.repository.user.mapper.UserFriendsRepository;
import com.alibaba.server.nio.repository.user.mapper.UserRepository;
import com.alibaba.server.nio.repository.user.repository.dataobject.UserFriendApplyDo;
import com.alibaba.server.nio.repository.user.service.FriendshipService;
import com.alibaba.server.nio.repository.user.service.dto.FriendRequestHandleResult;
import com.alibaba.server.nio.repository.user.service.dto.UserFriendApplyDTO;
import org.apache.commons.lang.StringUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

/** 好友关系领域服务实现。 */
@Service
public class FriendshipServiceImpl implements FriendshipService {

    private static final int ACTION_ACCEPT = 1;
    private static final int ACTION_REJECT = 2;
    private static final int MAX_REQUEST_MESSAGE_LENGTH = 255;
    private static final int MAX_ALIAS_LENGTH = 64;
    private static final int MAX_REQUESTS_PER_MINUTE = 10;
    private static final int MAX_REQUESTS_PER_DAY = 100;

    private final UserFriendApplyRepository userFriendApplyRepository;
    private final UserFriendsRepository userFriendsRepository;
    private final UserRepository userRepository;

    public FriendshipServiceImpl(UserFriendApplyRepository userFriendApplyRepository,
            UserFriendsRepository userFriendsRepository,
            UserRepository userRepository) {
        this.userFriendApplyRepository = userFriendApplyRepository;
        this.userFriendsRepository = userFriendsRepository;
        this.userRepository = userRepository;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserFriendApplyDTO sendRequest(Integer senderId, Integer receiverId, String requestMessage) {
        if (senderId == null || receiverId == null) {
            throw new IllegalArgumentException("好友ID不能为空");
        }
        if (senderId.equals(receiverId)) {
            throw new IllegalArgumentException("不能添加自己为好友");
        }
        if (userRepository.countActiveById(Long.valueOf(receiverId)) == 0) {
            throw new IllegalArgumentException("目标用户不存在或已停用");
        }
        if (userFriendsRepository.countActiveEitherDirection(senderId, receiverId) > 0) {
            throw new IllegalArgumentException("已经是好友了");
        }
        if (userFriendApplyRepository.findPending(senderId, receiverId) != null) {
            throw new IllegalArgumentException("已发送过申请，请等待对方处理");
        }
        if (userFriendApplyRepository.findPending(receiverId, senderId) != null) {
            throw new IllegalArgumentException("对方已向你发送好友申请，请到“新的朋友”中处理");
        }
        if (userFriendApplyRepository.countCreatedSince(senderId, 1) >= MAX_REQUESTS_PER_MINUTE) {
            throw new IllegalArgumentException("好友申请过于频繁，请稍后再试");
        }
        if (userFriendApplyRepository.countCreatedSince(senderId, 1440) >= MAX_REQUESTS_PER_DAY) {
            throw new IllegalArgumentException("今日好友申请次数已达上限");
        }

        String normalizedMessage = StringUtils.trimToEmpty(requestMessage);
        if (StringUtils.isBlank(normalizedMessage)) {
            normalizedMessage = "请求添加你为好友";
        }
        if (normalizedMessage.length() > MAX_REQUEST_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("验证消息不能超过255个字符");
        }

        UserFriendApplyDo apply = new UserFriendApplyDo();
        apply.setSenderId(senderId);
        apply.setReceiverId(receiverId);
        apply.setRequestMsg(normalizedMessage);
        apply.setStatus(0);
        apply.setDel("N");
        apply.setGmtCreated(new Date());
        apply.setGmtModified(new Date());
        try {
            userFriendApplyRepository.insertSelective(apply);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("已发送过申请，请等待对方处理", e);
        }
        return toDto(apply);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FriendRequestHandleResult handleRequest(Integer receiverId, Long requestId, Integer action, String alias) {
        if (receiverId == null || requestId == null) {
            throw new IllegalArgumentException("申请ID不能为空");
        }
        if (action == null || (action != ACTION_ACCEPT && action != ACTION_REJECT)) {
            throw new IllegalArgumentException("好友申请操作无效");
        }

        String normalizedAlias = StringUtils.trimToNull(alias);
        if (normalizedAlias != null && normalizedAlias.length() > MAX_ALIAS_LENGTH) {
            throw new IllegalArgumentException("好友备注不能超过64个字符");
        }

        UserFriendApplyDo apply = userFriendApplyRepository.findPendingForUpdate(requestId, receiverId);
        if (apply == null) {
            throw new IllegalArgumentException("申请不存在、无权处理或已经处理");
        }

        int updated = userFriendApplyRepository.updatePendingStatus(requestId, receiverId, action);
        if (updated != 1) {
            throw new IllegalStateException("好友申请状态已发生变化，请刷新后重试");
        }

        if (action == ACTION_ACCEPT) {
            userFriendsRepository.upsertActiveFriend(receiverId, apply.getSenderId(), normalizedAlias);
            userFriendsRepository.upsertActiveFriend(apply.getSenderId(), receiverId, null);
        }

        return new FriendRequestHandleResult(requestId, apply.getSenderId(), receiverId, action);
    }

    @Override
    public boolean isActiveFriend(Integer userId, Integer friendId) {
        return userId != null && friendId != null
                && userFriendsRepository.countActive(userId, friendId) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateAlias(Integer userId, Long friendshipId, String alias) {
        if (userId == null || friendshipId == null) {
            throw new IllegalArgumentException("好友关系ID不能为空");
        }
        String normalizedAlias = StringUtils.trimToNull(alias);
        if (normalizedAlias != null && normalizedAlias.length() > MAX_ALIAS_LENGTH) {
            throw new IllegalArgumentException("好友备注不能超过64个字符");
        }
        int updated = userFriendsRepository.updateOwnedAlias(friendshipId, userId, normalizedAlias);
        if (updated != 1) {
            throw new IllegalArgumentException("好友关系不存在或无权修改");
        }
    }

    private UserFriendApplyDTO toDto(UserFriendApplyDo apply) {
        UserFriendApplyDTO dto = new UserFriendApplyDTO();
        dto.setId(apply.getId());
        dto.setSenderId(apply.getSenderId());
        dto.setReceiverId(apply.getReceiverId());
        dto.setRequestMsg(apply.getRequestMsg());
        dto.setStatus(apply.getStatus());
        dto.setDel(apply.getDel());
        dto.setGmtCreated(apply.getGmtCreated());
        dto.setGmtModified(apply.getGmtModified());
        return dto;
    }
}
