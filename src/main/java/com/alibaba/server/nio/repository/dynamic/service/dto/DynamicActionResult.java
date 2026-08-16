package com.alibaba.server.nio.repository.dynamic.service.dto;

import lombok.Data;

/** 互动后的服务端权威计数和当前用户状态。 */
@Data
public class DynamicActionResult {
    private Long dynamicId;
    private String action;
    private String content;
    private int likeCount;
    private int replyCount;
    private int repostCount;
    private boolean liked;
    private boolean reposted;
}
