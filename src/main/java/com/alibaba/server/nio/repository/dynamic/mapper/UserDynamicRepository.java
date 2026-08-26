package com.alibaba.server.nio.repository.dynamic.mapper;

import com.alibaba.server.nio.core.repository.BaseMapperRepository;
import com.alibaba.server.nio.repository.dynamic.service.param.UserDynamicCreateParam;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Repository;

/**
 * 用户动态 Mapper
 */
@Repository
@Mapper
public interface UserDynamicRepository
        extends BaseMapperRepository<UserDynamicCreateParam, UserDynamicDO> {
}
