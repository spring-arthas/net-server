package com.alibaba.server.nio.service.chat.model;

import lombok.Data;

import java.nio.channels.SocketChannel;

/**
 * 聊天注册帧实体类
 *
 * @author spring
 * */

@Data
public class ChatMessageRegister {

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
     * 联系方式
     *
     * */
    private String phone;

    /**
     * 邮件
     *
     * */
    private String mail;

    /**
     * 成功注册时间
     *
     * */
    private String successRegisterTime;

}
