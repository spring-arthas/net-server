package com.alibaba.server.nio.repository.user.service.param;

import com.alibaba.server.nio.core.param.PageQueryParam;
import lombok.Data;

/**
 * 用户好友关系查询 param
 * 
 * @author spring
 */
@Data
public class UserFriendsQueryParam extends PageQueryParam {

    /** 用户ID */
    private Integer userId;

    /** 好友的用户ID */
    private Integer friendId;
}
