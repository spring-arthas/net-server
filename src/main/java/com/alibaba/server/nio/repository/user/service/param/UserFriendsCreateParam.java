package com.alibaba.server.nio.repository.user.service.param;

import lombok.Data;
import java.io.Serializable;

/**
 * 用户好友关系创建 param
 * 
 * @author spring
 */
@Data
public class UserFriendsCreateParam implements Serializable {

    /** 用户ID */
    private Integer userId;

    /** 好友的用户ID */
    private Integer friendId;

    /** 好友备注名 */
    private String alias;
}
