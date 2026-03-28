package com.alibaba.server.nio.repository.user.mapper;

import com.alibaba.server.nio.core.repository.BaseMapperRepository;
import com.alibaba.server.nio.repository.user.repository.dataobject.UserDo;
import com.alibaba.server.nio.repository.user.repository.param.UserDalQueryParam;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 用户dao
 * 
 * @author spring
 */
@Mapper
public interface UserRepository extends BaseMapperRepository<UserDalQueryParam, UserDo> {

    /**
     * @param param
     * @return
     */
    // String queryPayGroupCode(String param);

    /**
     * 模糊查询用户（用户名或昵称）
     *
     * @param param
     * @return
     */
    List<UserDo> queryFuzzy(UserDalQueryParam param);

    /**
     * 根据 ID 列表批量查询用户
     *
     * @param ids 用户 ID 列表
     * @return 用户列表
     */
    List<UserDo> listByIds(List<Long> ids);
}
