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
     * @param offset  起始偏移量
     * @param limit   返回条数限制
     * @return 聊天记录列表
     */
    List<UserFriendMessageDO> getChatHistory(Integer userId1, Integer userId2, int offset, int limit);

    /**
     * 获取指定发送者发给指定接收者的未读消息数量
     *
     * @param senderId   发送人ID
     * @param receiverId 接收人ID
     * @return 未读消息数量
     */
    int getUnreadMessageCount(Integer senderId, Integer receiverId);

    /**
     * 获取指定发送者发给指定接收者的最新未读消息
     *
     * @param senderId   发送人ID
     * @param receiverId 接收人ID
     * @return 最新未读消息内容数据（如果无则返回null）
     */
    UserFriendMessageDO getLatestUnreadMessage(Integer senderId, Integer receiverId);

    /**
     * 将发送方给接收方的所有未读消息标记为已读
     *
     * @param senderId   发送方用户 ID (对方)
     * @param receiverId 接收方用户 ID (当前用户)
     * @return 标记成功的消息条数
     */
    int updateMessageStatusRead(Integer senderId, Integer receiverId);
}
