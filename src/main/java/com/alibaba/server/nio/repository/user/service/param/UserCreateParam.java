package com.alibaba.server.nio.repository.user.service.param;

import lombok.Data;

import java.util.Date;

/**
 * 创建用户
 *
 * @author spring
 */

@Data
public class UserCreateParam {
    private String userName;

    private String nickName;

    private String password;

    private String phone;

    private String mail;

    private Date lastLoginDate;

    private String avatar;

    private Date registerDate;
}
