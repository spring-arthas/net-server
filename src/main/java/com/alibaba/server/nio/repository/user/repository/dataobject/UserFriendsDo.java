package com.alibaba.server.nio.repository.user.repository.dataobject;

import com.alibaba.server.nio.core.dataobject.BaseDO;
import com.alibaba.server.nio.core.facade.CloneableSupport;
import com.alibaba.server.nio.core.facade.Identity;
import lombok.Data;

/**
 * 用户好友关系 Do
 * 
 * @author spring
 */
@Data
public class UserFriendsDo extends BaseDO implements Identity, CloneableSupport {

    /** 省略id, gmtCreated, gmtModified (BaseDO中已有) */

    /** 用户ID */
    private Integer userId;

    /** 好友的用户ID */
    private Integer friendId;

    /** 好友备注名 */
    private String alias;

    /** 是否删除, N=否, 1=是 */
    private String del;
}
