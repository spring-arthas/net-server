package com.alibaba.server.nio.repository.chat.service.impl;

import com.alibaba.server.nio.repository.chat.mapper.UserFriendMessageDO;
import com.alibaba.server.nio.repository.chat.mapper.UserFriendMessageRepository;
import com.alibaba.server.nio.repository.chat.service.ChatHistoryPage;
import com.alibaba.server.nio.repository.chat.service.UserFriendMessageService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 聊天消息服务实现类
 */
@Service
@Slf4j
public class UserFriendMessageServiceImpl implements UserFriendMessageService {

    @Autowired
    private UserFriendMessageRepository chatMessageRepository;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserFriendMessageDO saveMessage(Integer senderId, Integer receiverId, String content, String msgType) {
        if (senderId == null || receiverId == null) {
            throw new IllegalArgumentException("发送方或接收方ID不能为空");
        }

        UserFriendMessageDO msg = new UserFriendMessageDO();
        msg.setSenderId(senderId);
        msg.setReceiverId(receiverId);
        msg.setContent(content);
        msg.setMsgType(msgType == null ? "TEXT" : msgType);
        msg.setStatus(0); // 0-未读
        // BaseDO settings
        msg.setDel("N");
        msg.setDelTime(null);
        msg.setGmtCreated(new Date());
        msg.setGmtModified(new Date());

        try {
            chatMessageRepository.insertSelective(msg);
            return msg;
        } catch (Exception e) {
            log.error("保存聊天消息失败, senderId={}, receiverId={}, error={}", senderId, receiverId, e.getMessage());
            throw new RuntimeException("保存聊天记录失败", e);
        }
    }

    @Override
    public List<UserFriendMessageDO> getChatHistory(Integer userId1, Integer userId2, int offset, int limit) {
        if (userId1 == null || userId2 == null) {
            return Collections.emptyList();
        }
        return chatMessageRepository.getChatHistory(userId1, userId2, Math.max(0, offset), limit > 0 ? limit : 50);
    }

    @Override
    public ChatHistoryPage getChatHistoryPage(Integer userId1, Integer userId2,
            Long beforeMessageId, Long afterMessageId, Integer legacyOffset, int limit) {
        if (userId1 == null || userId2 == null) {
            return new ChatHistoryPage(Collections.emptyList(), false, null, null);
        }
        if (beforeMessageId != null && afterMessageId != null) {
            throw new IllegalArgumentException("beforeMessageId 和 afterMessageId 不能同时传入");
        }

        int pageSize = limit > 0 ? Math.min(limit, 100) : 50;
        int fetchSize = pageSize + 1;
        List<UserFriendMessageDO> fetched;
        boolean descending = false;

        if (beforeMessageId != null) {
            fetched = chatMessageRepository.getChatHistoryBefore(
                    userId1, userId2, beforeMessageId, fetchSize);
            descending = true;
        } else if (afterMessageId != null) {
            fetched = chatMessageRepository.getChatHistoryAfter(
                    userId1, userId2, afterMessageId, fetchSize);
        } else if (legacyOffset != null) {
            fetched = chatMessageRepository.getChatHistory(
                    userId1, userId2, Math.max(0, legacyOffset), fetchSize);
        } else {
            fetched = chatMessageRepository.getLatestChatHistory(userId1, userId2, fetchSize);
            descending = true;
        }

        List<UserFriendMessageDO> safeFetched = fetched == null ? Collections.emptyList() : fetched;
        boolean hasMore = safeFetched.size() > pageSize;
        int pageEnd = Math.min(pageSize, safeFetched.size());
        int pageStart = 0;
        if (legacyOffset != null && safeFetched.size() > pageSize) {
            // The legacy mapper returns the over-fetched rows in ascending order,
            // so the extra row is the oldest item at the front of the list.
            pageStart = safeFetched.size() - pageSize;
            pageEnd = safeFetched.size();
        }
        List<UserFriendMessageDO> pageMessages = new ArrayList<>(
                safeFetched.subList(pageStart, pageEnd));
        if (descending) {
            Collections.reverse(pageMessages);
        }

        Long nextBeforeMessageId = pageMessages.isEmpty() ? null : pageMessages.get(0).getId();
        Long latestMessageId = pageMessages.isEmpty() ? null
                : pageMessages.get(pageMessages.size() - 1).getId();
        return new ChatHistoryPage(pageMessages, hasMore, nextBeforeMessageId, latestMessageId);
    }

    @Override
    public int getUnreadMessageCount(Integer senderId, Integer receiverId) {
        if (senderId == null || receiverId == null) {
            return 0;
        }
        return chatMessageRepository.getUnreadMessageCount(senderId, receiverId);
    }

    @Override
    public UserFriendMessageDO getLatestUnreadMessage(Integer senderId, Integer receiverId) {
        if (senderId == null || receiverId == null) {
            return null;
        }
        return chatMessageRepository.getLatestUnreadMessage(senderId, receiverId);
    }

    @Override
    public int updateMessageStatusRead(Integer senderId, Integer receiverId) {
        if (senderId == null || receiverId == null) {
            return 0;
        }
        return chatMessageRepository.updateMessageStatusRead(senderId, receiverId);
    }
}
