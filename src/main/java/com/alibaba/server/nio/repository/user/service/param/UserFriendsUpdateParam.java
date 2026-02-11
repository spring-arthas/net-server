package com.alibaba.server.nio.repository.user.service.param;

import lombok.Data;
import java.io.Serializable;

/**
 * 用户好友关系更新 param
 * 
 * @author spring
 */
@Data
public class UserFriendsUpdateParam implements Serializable {

    private Long id;

    /** 好友备注名 */
    private String alias;

    /** 是否删除 */
    private String del;
}
