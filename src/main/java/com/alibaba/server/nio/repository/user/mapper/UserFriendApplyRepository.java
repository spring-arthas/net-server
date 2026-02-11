package com.alibaba.server.nio.repository.user.mapper;

import com.alibaba.server.nio.core.repository.BaseMapperRepository;
import com.alibaba.server.nio.repository.user.repository.dataobject.UserFriendApplyDo;
import com.alibaba.server.nio.repository.user.repository.param.UserFriendApplyDalQueryParam;
import org.apache.ibatis.annotations.Mapper;

/**
 * 好友添加请求 dao
 * 
 * @author spring
 */
@Mapper
public interface UserFriendApplyRepository
        extends BaseMapperRepository<UserFriendApplyDalQueryParam, UserFriendApplyDo> {
}
