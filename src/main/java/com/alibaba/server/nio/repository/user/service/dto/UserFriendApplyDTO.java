package com.alibaba.server.nio.repository.user.service.dto;

import com.alibaba.server.nio.core.dataobject.BaseDO;
import lombok.Data;
import java.io.Serializable;
import java.util.Date;

/**
 * 好友添加请求 DTO
 * 
 * @author spring
 */
@Data
public class UserFriendApplyDTO implements Serializable {

    private Long id;

    /** 发送者用户ID */
    private Integer senderId;

    /** 接收者用户ID */
    private Integer receiverId;

    /** 验证消息 */
    private String requestMsg;

    /** 状态: 0=待处理, 1=已同意, 2=已拒绝 */
    private Integer status;

    /** 是否删除 */
    private String del;

    private Date gmtCreated;

    private Date gmtModified;
}
