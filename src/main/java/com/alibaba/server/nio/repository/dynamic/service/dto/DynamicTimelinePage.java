package com.alibaba.server.nio.repository.dynamic.service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/** 游标分页时间线。 */
@Data
@AllArgsConstructor
public class DynamicTimelinePage {
    private List<DynamicPostDTO> posts;
    private Long nextBeforeId;
    private boolean hasMore;
}
