package com.alibaba.server.nio.repository.dynamic.service.dto;

import lombok.Data;

import java.util.Collections;
import java.util.List;

/** 客户端动态卡片。 */
@Data
public class DynamicPostDTO {
    private Long id;
    private DynamicAuthorDTO author;
    private String content;
    private List<DynamicMediaDTO> media = Collections.emptyList();
    private DynamicReferenceDTO reference;
    private int likeCount;
    private int replyCount;
    private int repostCount;
    private boolean liked;
    private boolean reposted;
    private DynamicPostDTO originalPost;
    private long createdAt;
    private boolean mine;
}
