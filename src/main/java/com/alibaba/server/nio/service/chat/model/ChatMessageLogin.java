package com.alibaba.server.nio.service.chat.model;

import lombok.Data;

import java.nio.channels.SocketChannel;

/**
 * 聊天登录帧实体类
 *
 * @author spring
 * */

@Data
public class ChatMessageLogin {

    /**
     * 对应Socket连接
     *
     * */
    private SocketChannel socketChannel;

    /**
     * 用户名
     *
     * */
    private String userName;

    /**
     * 密码
     *
     * */
    private String password;

    /**
     * 成功登陆时间
     *
     * */
    private String successLoginTime;

    /**
     * 登录状态
     *
     * */
    private String status;
}
