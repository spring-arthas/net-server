package com.alibaba.server.nio.repository.chat.mapper;

import com.alibaba.server.nio.core.dataobject.BaseDO;
import java.io.Serializable;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 聊天消息记录表(user_friend_message)实体类
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UserFriendMessageDO extends BaseDO implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Long id;

    /**
     * 发送方用户ID
     */
    private Integer senderId;

    /**
     * 接收方用户ID
     */
    private Integer receiverId;

    /**
     * 消息内容
     */
    private String content;

    /**
     * 消息类型: TEXT, IMAGE, FILE等
     */
    private String msgType;

    /**
     * 状态: 0-未读, 1-已读
     */
    private Integer status;

}
