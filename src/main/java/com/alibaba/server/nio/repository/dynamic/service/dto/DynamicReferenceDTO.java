package com.alibaba.server.nio.repository.dynamic.service.dto;

import lombok.Data;

import java.util.List;

/** 聊天消息或网盘文件引用卡片。 */
@Data
public class DynamicReferenceDTO {
    private String sourceType;
    private String sourceId;
    private String title;
    private String subtitle;
    private List<DynamicMediaDTO> media;
}
