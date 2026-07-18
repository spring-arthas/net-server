package com.alibaba.server.nio.repository.user.service.param;

import com.alibaba.server.nio.core.param.PageQueryParam;
import lombok.Data;

/**
 * 好友添加请求查询 param
 * 
 * @author spring
 */
@Data
public class UserFriendApplyQueryParam extends PageQueryParam {

    /** 发送者用户ID */
    private Integer senderId;

    /** 接收者用户ID */
    private Integer receiverId;

    /** 状态 */
    private Integer status;
}
