package com.alibaba.server.nio.repository.dynamic.service;

import com.alibaba.server.nio.repository.dynamic.service.dto.DynamicActionResult;
import com.alibaba.server.nio.repository.dynamic.service.dto.DynamicCreateResult;
import com.alibaba.server.nio.repository.dynamic.service.dto.DynamicDetailResult;
import com.alibaba.server.nio.repository.dynamic.service.dto.DynamicTimelinePage;
import com.alibaba.server.nio.repository.dynamic.service.param.UserDynamicCreateParam;

/** 动态领域服务。所有入口都必须使用已登录用户 ID。 */
public interface UserDynamicService {

    DynamicCreateResult create(Long userId, UserDynamicCreateParam param);

    DynamicTimelinePage timeline(Long userId, String scope, Long beforeId, int limit);

    DynamicActionResult action(Long userId, Long dynamicId, String action, String content);

    DynamicDetailResult detail(Long userId, Long dynamicId, Long beforeReplyId, int limit);

    void delete(Long userId, Long dynamicId);

    /** 保留旧调用签名，旧客户端只发 content/imagePaths 时仍可发布。 */
    Long createDynamic(Long userId, String content, String imagePaths);
}
