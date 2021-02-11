package com.alibaba.server.nio.repository.user.model;

import lombok.Data;

import java.util.Date;

/**
 * 用户实体类
 *
 * @author spring
 * */

@Data
public class UserEntity {

    private String userName;

    private String password;

    private Date lastLoginDate;

    private Date registerDate;

    private String register;

    private String status;
}
