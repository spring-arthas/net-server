package com.alibaba.server.nio.repository.user.mapper;

import com.alibaba.server.nio.core.repository.BaseMapperRepository;
import com.alibaba.server.nio.repository.user.repository.dataobject.UserFriendsDo;
import com.alibaba.server.nio.repository.user.repository.param.UserFriendsDalQueryParam;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户好友关系 dao
 * 
 * @author spring
 */
@Mapper
public interface UserFriendsRepository extends BaseMapperRepository<UserFriendsDalQueryParam, UserFriendsDo> {
}
