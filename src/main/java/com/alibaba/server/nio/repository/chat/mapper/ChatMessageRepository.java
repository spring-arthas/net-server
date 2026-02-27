package com.alibaba.server.nio.repository.chat.mapper;

import com.alibaba.server.nio.core.repository.BaseMapperRepository;
import com.alibaba.server.nio.repository.chat.service.param.ChatMessageQueryParam;
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
public interface ChatMessageRepository extends BaseMapperRepository<ChatMessageQueryParam, ChatMessageDO> {

    @Select("SELECT * FROM mds_chat_message WHERE (sender_id = #{userId1} AND receiver_id = #{userId2}) OR (sender_id = #{userId2} AND receiver_id = #{userId1}) ORDER BY gmt_created ASC LIMIT #{limit}")
    List<ChatMessageDO> getChatHistory(@Param("userId1") Integer userId1, @Param("userId2") Integer userId2,
            @Param("limit") int limit);
}
