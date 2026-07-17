package com.alibaba.server.nio.repository.user.service.param;

import lombok.Data;
import java.io.Serializable;

/**
 * 好友添加请求创建 param
 * 
 * @author spring
 */
@Data
public class UserFriendApplyCreateParam implements Serializable {

    /** 发送者用户ID */
    private Integer senderId;

    /** 接收者用户ID */
    private Integer receiverId;

    /** 验证消息 */
    private String requestMsg;
}
