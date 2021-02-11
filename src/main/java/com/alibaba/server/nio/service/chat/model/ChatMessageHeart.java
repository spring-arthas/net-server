package com.alibaba.server.nio.service.chat.model;

import lombok.Data;

/**
 * 聊天登录帧实体类
 *
 * @author spring
 * */
@Data
public class ChatMessageHeart {
    /**
     * 当前用户
     *
     * */
    private String userName;

    /**
     * 心跳间隔
     *
     * */
    private String heartInterval;

    /**
     * 心跳数据
     *
     * */
    private String data;
}
