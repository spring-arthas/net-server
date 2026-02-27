package com.alibaba.server.nio.repository.chat.service;

import com.alibaba.server.nio.repository.chat.mapper.UserFriendMessageDO;
import java.util.List;

/**
 * 聊天消息服务接口
 */
public interface UserFriendMessageService {

    /**
     * 保存单条聊天记录
     *
     * @param senderId   发送人ID
     * @param receiverId 接收人ID
     * @param content    消息内容
     * @param msgType    消息类型
     * @return 保存成功后的实体
     */
    UserFriendMessageDO saveMessage(Integer senderId, Integer receiverId, String content, String msgType);

    /**
     * 获取两人之间的聊天记录
     *
     * @param userId1 用户1 ID
     * @param userId2 用户2 ID
     * @param limit   返回条数限制
     * @return 聊天记录列表
     */
    List<UserFriendMessageDO> getChatHistory(Integer userId1, Integer userId2, int limit);
}
