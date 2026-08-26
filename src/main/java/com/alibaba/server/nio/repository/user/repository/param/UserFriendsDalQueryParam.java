package com.alibaba.server.nio.repository.user.repository.param;

import com.alibaba.server.nio.core.param.DalPageQueryParam;
import lombok.Data;

/**
 * 用户好友关系查询 dal param
 * 
 * @author spring
 */
@Data
public class UserFriendsDalQueryParam extends DalPageQueryParam {

    /** 用户ID */
    private Integer userId;

    /** 好友的用户ID */
    private Integer friendId;

    /** 是否删除 */
    private String del;
}
