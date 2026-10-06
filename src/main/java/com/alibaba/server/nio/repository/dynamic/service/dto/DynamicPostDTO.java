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
    private Long replyToCommentId;
    private Long dynamicId;
    private long createdAt;
    private boolean mine;
    /** 该动态的评论列表（时间线接口返回，包含5条顶级评论+所有回复） */
    private List<DynamicPostDTO> replies = Collections.emptyList();
}
