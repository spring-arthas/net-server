package com.alibaba.server.nio.repository.chat.service.impl;

import com.alibaba.server.nio.repository.chat.mapper.UserFriendMessageDO;
import com.alibaba.server.nio.repository.chat.mapper.UserFriendMessageRepository;
import com.alibaba.server.nio.repository.chat.service.UserFriendMessageService;
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
}
