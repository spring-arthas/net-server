package com.alibaba.server.nio.repository.user.service.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 好友搜索专用响应对象。
 *
 * 该对象只包含添加好友页面需要展示的公开字段，禁止复用包含密码、邮箱等敏感信息的 UserDTO。
 */
@Data
public class UserSearchDTO implements Serializable {

    private Long userId;

    private String userName;

    private String nickName;

    private String avatar;

    /** 0=已申请, 1=已是好友, 2=曾被拒绝, 3=可添加, 4=对方已申请当前用户 */
    private Integer friendStatus;

    private String friendStatusDesc;

    /** 对方已向当前用户发出申请时返回，便于客户端跳转处理。 */
    private Long incomingRequestId;
}
