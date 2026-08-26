package com.alibaba.server.nio.repository.dynamic.service;

/**
 * 用户动态 Service 接口
 */
public interface UserDynamicService {

    /**
     * 创建用户动态
     *
     * @param userId     发布者用户ID
     * @param content    动态文字内容，不超过500字
     * @param imagePaths 图片路径，逗号分隔，不超过9张，可为null
     * @return 新建动态的 id
     */
    Long createDynamic(Long userId, String content, String imagePaths);
}
