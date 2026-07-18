package com.alibaba.server.nio.repository.dynamic.mapper;

import com.alibaba.server.nio.core.dataobject.BaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 用户动态表(user_dynamic)实体类
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UserDynamicDO extends BaseDO implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Long id;

    /**
     * 发布者用户ID
     */
    private Long userId;

    /**
     * 动态文字内容，最多500字
     */
    private String content;

    /**
     * 图片路径，逗号分隔，最多9张
     */
    private String imagePaths;
}
