package com.alibaba.server.nio.service.chat.model;

import lombok.Data;

/**
 * 聊天登出帧实体类
 *
 * @author spring
 * */

@Data
public class ChatMessageLogout {

    /**
     * 用户名
     *
     * */
    private String userName;
}
