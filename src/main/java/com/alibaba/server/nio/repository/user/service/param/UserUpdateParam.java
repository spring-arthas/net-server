package com.alibaba.server.nio.repository.user.service.param;

import lombok.Data;

import java.util.Date;

/**
 * 用户更新 service param
 *
 * @author spring
 */

@Data
public class UserUpdateParam {

    private Long id;

    private String userName;

    private String nickName;

    private String password;

    private String phone;

    private String mail;

    private Date lastLoginDate;

    private Date registerDate;
}
