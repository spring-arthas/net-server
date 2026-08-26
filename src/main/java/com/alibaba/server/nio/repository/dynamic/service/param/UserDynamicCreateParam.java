package com.alibaba.server.nio.repository.dynamic.service.param;

import com.alibaba.server.nio.core.param.DalPageQueryParam;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class UserDynamicCreateParam extends DalPageQueryParam {
    private Long id;
    private Long userId;
    private String content;
    private String imagePaths;
}
