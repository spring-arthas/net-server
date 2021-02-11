package com.alibaba.server.nio.service.chat.model;

import lombok.Data;

/**
 * 聊天文本帧实体类
 *
 * @author spring
 * */

@Data
public class ChatMessageText {

    /**
     * 当前用户
     *
     * */
    private String currentUserName;

    /**
     * 对方用户
     *
     */
    private String remoteUserName;

    /**
     * 发送内容
     *
     * */
    private String content;
}
