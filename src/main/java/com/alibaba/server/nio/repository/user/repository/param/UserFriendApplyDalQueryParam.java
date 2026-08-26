package com.alibaba.server.nio.repository.user.repository.param;

import com.alibaba.server.nio.core.param.DalPageQueryParam;
import lombok.Data;

/**
 * 好友添加请求查询 dal param
 * 
 * @author spring
 */
@Data
public class UserFriendApplyDalQueryParam extends DalPageQueryParam {

    /** 发送者用户ID */
    private Integer senderId;

    /** 接收者用户ID */
    private Integer receiverId;

    /** 状态 */
    private Integer status;

    /** 是否删除 */
    private String del;
}
