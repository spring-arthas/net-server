package com.alibaba.server.nio.repository.chat.service.impl;

import com.alibaba.server.nio.repository.chat.mapper.ChatMessageDO;
import com.alibaba.server.nio.repository.chat.mapper.ChatMessageRepository;
import com.alibaba.server.nio.repository.chat.service.ChatMessageService;
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
public class ChatMessageServiceImpl implements ChatMessageService {

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChatMessageDO saveMessage(Integer senderId, Integer receiverId, String content, String msgType) {
        if (senderId == null || receiverId == null) {
            throw new IllegalArgumentException("发送方或接收方ID不能为空");
        }

        ChatMessageDO msg = new ChatMessageDO();
        msg.setSenderId(senderId);
        msg.setReceiverId(receiverId);
        msg.setContent(content);
        msg.setMsgType(msgType == null ? "TEXT" : msgType);
        msg.setStatus(0); // 0-未读
        // BaseDO settings
        msg.setDel("N");
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
    public List<ChatMessageDO> getChatHistory(Integer userId1, Integer userId2, int limit) {
        if (userId1 == null || userId2 == null) {
            return Collections.emptyList();
        }
        return chatMessageRepository.getChatHistory(userId1, userId2, limit > 0 ? limit : 50);
    }
}
