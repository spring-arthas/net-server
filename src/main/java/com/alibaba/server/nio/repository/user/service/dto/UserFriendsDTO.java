package com.alibaba.server.nio.repository.user.service.dto;

import lombok.Data;
import java.io.Serializable;
import java.util.Date;

/**
 * 用户好友关系 DTO
 * 
 * @author spring
 */
@Data
public class UserFriendsDTO implements Serializable {

    private Long id;

    /** 用户ID */
    private Integer userId;

    /** 好友的用户ID */
    private Integer friendId;

    /** 好友备注名 */
    private String alias;

    /** 是否删除 */
    private String del;

    private Date gmtCreated;

    private Date gmtModified;
}
