package com.alibaba.server.nio.repository.chat.mapper;

import com.alibaba.server.nio.core.repository.BaseMapperRepository;
import com.alibaba.server.nio.repository.chat.service.param.UserFriendMessageQueryParam;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Repository;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 聊天消息记录表(mds_chat_message) Mapper
 */
@Repository
@Mapper
public interface UserFriendMessageRepository
                extends BaseMapperRepository<UserFriendMessageQueryParam, UserFriendMessageDO> {

        @Select("SELECT * FROM (SELECT * FROM user_friend_message WHERE del = 'N' AND ((sender_id = #{userId1} AND receiver_id = #{userId2}) OR (sender_id = #{userId2} AND receiver_id = #{userId1})) ORDER BY gmt_created DESC LIMIT #{offset}, #{limit}) AS temp ORDER BY gmt_created ASC")
        List<UserFriendMessageDO> getChatHistory(@Param("userId1") Integer userId1, @Param("userId2") Integer userId2,
                        @Param("offset") int offset, @Param("limit") int limit);

        @Select("SELECT * FROM user_friend_message WHERE del = 'N' AND ((sender_id = #{userId1} AND receiver_id = #{userId2}) OR (sender_id = #{userId2} AND receiver_id = #{userId1})) ORDER BY id DESC LIMIT #{limit}")
        List<UserFriendMessageDO> getLatestChatHistory(@Param("userId1") Integer userId1,
                        @Param("userId2") Integer userId2, @Param("limit") int limit);

        @Select("SELECT * FROM user_friend_message WHERE del = 'N' AND ((sender_id = #{userId1} AND receiver_id = #{userId2}) OR (sender_id = #{userId2} AND receiver_id = #{userId1})) AND id < #{beforeMessageId} ORDER BY id DESC LIMIT #{limit}")
        List<UserFriendMessageDO> getChatHistoryBefore(@Param("userId1") Integer userId1,
                        @Param("userId2") Integer userId2, @Param("beforeMessageId") Long beforeMessageId,
                        @Param("limit") int limit);

        @Select("SELECT * FROM user_friend_message WHERE del = 'N' AND ((sender_id = #{userId1} AND receiver_id = #{userId2}) OR (sender_id = #{userId2} AND receiver_id = #{userId1})) AND id > #{afterMessageId} ORDER BY id ASC LIMIT #{limit}")
        List<UserFriendMessageDO> getChatHistoryAfter(@Param("userId1") Integer userId1,
                        @Param("userId2") Integer userId2, @Param("afterMessageId") Long afterMessageId,
                        @Param("limit") int limit);

        @Select("SELECT COUNT(*) FROM user_friend_message WHERE sender_id = #{senderId} AND receiver_id = #{receiverId} AND status = 0 AND del = 'N'")
        int getUnreadMessageCount(@Param("senderId") Integer senderId, @Param("receiverId") Integer receiverId);

        @Select("SELECT * FROM user_friend_message WHERE sender_id = #{senderId} AND receiver_id = #{receiverId} AND del = 'N' ORDER BY gmt_created DESC LIMIT 1")
        UserFriendMessageDO getLatestUnreadMessage(@Param("senderId") Integer senderId,
                        @Param("receiverId") Integer receiverId);

        @Update("UPDATE user_friend_message SET status = 1 WHERE sender_id = #{senderId} AND receiver_id = #{receiverId} AND status = 0 AND del = 'N'")
        int updateMessageStatusRead(@Param("senderId") Integer senderId, @Param("receiverId") Integer receiverId);

        @Select("SELECT COUNT(1) FROM user_friend_message "
                        + "WHERE del = 'N' AND (sender_id = #{userId} OR receiver_id = #{userId}) "
                        + "AND content REGEXP CONCAT('\\\"(fileId|previewFileId|thumbnailFileId)\\\"[[:space:]]*:[[:space:]]*', "
                        + "#{fileId}, '([^0-9]|$)')")
        int countAttachmentReferencesForUser(@Param("userId") Long userId, @Param("fileId") Long fileId);
}
