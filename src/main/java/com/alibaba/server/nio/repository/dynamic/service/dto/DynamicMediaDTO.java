package com.alibaba.server.nio.repository.dynamic.service.dto;

import lombok.Data;

/** 动态媒体附件。 */
@Data
public class DynamicMediaDTO {
    private String kind;
    private Long fileId;
    private String fileName;
    private Long fileSize;
    private String mimeType;
}
