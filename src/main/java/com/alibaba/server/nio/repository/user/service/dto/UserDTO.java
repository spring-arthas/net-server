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

    /**
     * 好友状态: 0-已申请, 1-已是好友, 2-已拒绝, 3-未添加
     */
    private Integer friendStatus;
}
