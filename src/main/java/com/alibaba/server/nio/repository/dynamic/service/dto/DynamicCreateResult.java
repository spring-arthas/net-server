package com.alibaba.server.nio.repository.dynamic.service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/** 发布成功后的权威动态。 */
@Data
@AllArgsConstructor
public class DynamicCreateResult {
    private Long dynamicId;
    private DynamicPostDTO post;
}
