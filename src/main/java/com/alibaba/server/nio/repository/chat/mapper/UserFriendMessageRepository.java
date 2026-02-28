package com.alibaba.server.nio.repository.chat.mapper;

import com.alibaba.server.nio.core.repository.BaseMapperRepository;
import com.alibaba.server.nio.repository.chat.service.param.UserFriendMessageQueryParam;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Repository;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 聊天消息记录表(mds_chat_message) Mapper
 */
@Repository
@Mapper
public interface UserFriendMessageRepository
        extends BaseMapperRepository<UserFriendMessageQueryParam, UserFriendMessageDO> {

    @Select("SELECT * FROM user_friend_message WHERE (sender_id = #{userId1} AND receiver_id = #{userId2}) OR (sender_id = #{userId2} AND receiver_id = #{userId1}) ORDER BY gmt_created DESC LIMIT #{limit}")
    List<UserFriendMessageDO> getChatHistory(@Param("userId1") Integer userId1, @Param("userId2") Integer userId2,
            @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM user_friend_message WHERE sender_id = #{senderId} AND receiver_id = #{receiverId} AND status = 0 AND del = 'N'")
    int getUnreadMessageCount(@Param("senderId") Integer senderId, @Param("receiverId") Integer receiverId);

    @Select("SELECT * FROM user_friend_message WHERE sender_id = #{senderId} AND receiver_id = #{receiverId} AND status = 0 AND del = 'N' ORDER BY gmt_created DESC LIMIT 1")
    UserFriendMessageDO getLatestUnreadMessage(@Param("senderId") Integer senderId,
            @Param("receiverId") Integer receiverId);
}
