package com.alibaba.server.nio.repository.user.service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/** 好友置顶状态更新结果。 */
@Data
@AllArgsConstructor
public class FriendPinUpdateResult implements Serializable {

    private Long relationshipId;

    private boolean pinned;

    private Date pinnedAt;
}
