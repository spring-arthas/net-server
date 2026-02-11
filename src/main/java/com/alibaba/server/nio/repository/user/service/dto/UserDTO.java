package com.alibaba.server.nio.repository.user.service.dto;

import com.alibaba.server.nio.core.service.BaseDTO;
import lombok.Data;
import java.util.Date;
import java.util.Map;

/**
 * 用户实体类
 *
 * @author spring
 */

@Data
public class UserDTO extends BaseDTO {

    private String userName;

    private String nickName;

    private String password;

    private String phone;

    private String mail;

    private String avatar;

    private Date lastLoginDate;

    private Date registerDate;

    /**
     * 好友状态描述 (e.g. "已是好友", "已申请", "添加")
     */
    private String friendStatusDesc;

    private Integer friendStatus;
}
