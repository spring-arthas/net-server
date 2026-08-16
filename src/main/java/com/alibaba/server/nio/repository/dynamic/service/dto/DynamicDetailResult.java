package com.alibaba.server.nio.repository.dynamic.service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/** 动态详情和回复游标。 */
@Data
@AllArgsConstructor
public class DynamicDetailResult {
    private DynamicPostDTO post;
    private List<DynamicPostDTO> replies;
    private Long nextBeforeReplyId;
    private boolean hasMore;
}
