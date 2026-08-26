package com.alibaba.server.nio.repository.user.repository.dataobject;

import com.alibaba.server.nio.core.dataobject.BaseDO;
import com.alibaba.server.nio.core.facade.CloneableSupport;
import com.alibaba.server.nio.core.facade.Identity;
import lombok.Data;

/**
 * 好友添加请求 Do
 * 
 * @author spring
 */
@Data
public class UserFriendApplyDo extends BaseDO implements Identity, CloneableSupport {

    /** 省略id, gmtCreated, gmtModified (BaseDO中已有) */

    /** 发送者用户ID */
    private Integer senderId;

    /** 接收者用户ID */
    private Integer receiverId;

    /** 验证消息 */
    private String requestMsg;

    /** 状态: 0=待处理, 1=已同意, 2=已拒绝 */
    private Integer status;

    /** 是否删除, N=否, 1=是 */
    private String del;
}
