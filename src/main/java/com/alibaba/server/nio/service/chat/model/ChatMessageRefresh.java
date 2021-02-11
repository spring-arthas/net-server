package com.alibaba.server.nio.service.chat.model;

import lombok.Data;

/**
 * 聊天刷新帧实体类
 * @author spring
 * */

@Data
public class ChatMessageRefresh {

    /**
     * 当前用户
     *
     * */
    private String userName;

    /**
     * 是否刷新
     *
     * */
    private String refresh;
}
