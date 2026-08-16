package com.alibaba.server.nio.repository.dynamic.mapper;

import com.alibaba.server.nio.core.dataobject.BaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 点赞、回复和转发记录。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UserDynamicInteractionDO extends BaseDO {
    private Long dynamicId;
    private Long userId;
    private String actionType;
    private String content;
    private String idempotencyKey;

    // [修改] 回复详情联表展示字段。
    private String userName;
    private String nickName;
    private String avatar;
}
