package com.alibaba.server.nio.repository.user.service.param;

import lombok.Data;
import java.io.Serializable;

/**
 * 好友添加请求更新 param
 * 
 * @author spring
 */
@Data
public class UserFriendApplyUpdateParam implements Serializable {

    private Long id;

    /** 状态: 0=待处理, 1=已同意, 2=已拒绝 */
    private Integer status;

    /** 是否删除 */
    private String del;
}
