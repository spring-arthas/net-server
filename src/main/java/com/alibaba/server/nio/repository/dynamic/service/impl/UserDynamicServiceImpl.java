package com.alibaba.server.nio.repository.dynamic.service.impl;

import com.alibaba.server.nio.repository.dynamic.mapper.UserDynamicDO;
import com.alibaba.server.nio.repository.dynamic.mapper.UserDynamicRepository;
import com.alibaba.server.nio.repository.dynamic.service.UserDynamicService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

/**
 * 用户动态 Service 实现
 */
@Service
@Slf4j
public class UserDynamicServiceImpl implements UserDynamicService {

    @Autowired
    private UserDynamicRepository userDynamicRepository;

    /**
     * 创建用户动态，持久化到 user_dynamic 表
     *
     * @param userId     发布者用户ID
     * @param content    动态文字内容，不超过500字
     * @param imagePaths 图片路径，逗号分隔，不超过9张，可为null
     * @return 新建动态的 id
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createDynamic(Long userId, String content, String imagePaths) {
        log.info("createDynamic, userId={}, contentLen={}, imagePaths={}", userId, content.length(), imagePaths);

        UserDynamicDO dynamic = new UserDynamicDO();
        dynamic.setUserId(userId);
        dynamic.setContent(content);
        dynamic.setImagePaths(imagePaths);
        dynamic.setDel("N");
        dynamic.setGmtCreated(new Date());
        dynamic.setGmtModified(new Date());

        try {
            userDynamicRepository.insertSelective(dynamic);
            log.info("createDynamic success, dynamicId={}", dynamic.getId());
            return dynamic.getId();
        } catch (Exception e) {
            log.error("createDynamic failed, userId={}, error={}", userId, e.getMessage(), e);
            throw new RuntimeException("创建动态失败", e);
        }
    }
}
