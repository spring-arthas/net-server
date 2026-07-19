package com.alibaba.server.nio.repository.user.service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.Serializable;

/** 好友申请处理结果，用于响应和在线事件推送。 */
@Data
@AllArgsConstructor
public class FriendRequestHandleResult implements Serializable {

    private Long requestId;

    private Integer senderId;

    private Integer receiverId;

    private Integer action;
}
