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

    ChatHistoryPage getChatHistoryPage(Integer userId1, Integer userId2,
            Long beforeMessageId, Long afterMessageId, Integer legacyOffset, int limit);

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

    /**
     * 按关键词搜索当前用户参与的消息
     *
     * @param userId  当前用户ID
     * @param keyword 搜索关键词
     * @param limit   返回条数上限
     * @return 匹配消息列表(按时间倒序)
     */
    List<UserFriendMessageDO> searchMessages(Integer userId, String keyword, int limit);

    /**
     * 按关键词搜索消息；friendId 为空时保留旧版全局搜索语义。
     *
     * @param userId   当前登录用户ID
     * @param friendId 指定好友ID，可为空
     * @param keyword  搜索关键词
     * @param limit    返回条数上限
     * @return 匹配消息列表(按时间倒序)
     */
    List<UserFriendMessageDO> searchMessages(Integer userId, Integer friendId, String keyword, int limit);

    /**
     * 按关键词搜索消息并返回权威分页状态。
     *
     * @param userId 当前登录用户ID
     * @param friendId 指定好友ID，可为空
     * @param keyword 搜索关键词
     * @param limit 单页条数
     * @return 搜索结果及是否还有下一页
     */
    ChatMessageSearchPage searchMessagesPage(Integer userId, Integer friendId, String keyword, int limit);

    /**
     * 更新消息表情回应
     *
     * @param messageId 消息ID
     * @param reaction  表情回应JSON
     * @return 更新条数
     */
    int updateMessageReaction(Long messageId, String reaction);

    /**
     * 撤回消息
     *
     * @param messageId 消息ID
     * @return 更新条数
     */
    int retractMessage(Long messageId);

    /**
     * 根据消息ID查询消息
     *
     * @param messageId 消息ID
     * @return 消息实体(不存在返回null)
     */
    UserFriendMessageDO getMessageById(Long messageId);
}
