package com.alibaba.server.nio.repository.dynamic.service.param;

import com.alibaba.server.nio.repository.dynamic.service.dto.DynamicMediaDTO;
import com.alibaba.server.nio.repository.dynamic.service.dto.DynamicReferenceDTO;
import lombok.Data;

import java.util.List;

/** 发布动态请求。 */
@Data
public class UserDynamicCreateParam {
    private String content;
    private List<DynamicMediaDTO> media;
    private String imagePaths;
    private DynamicReferenceDTO reference;
}
