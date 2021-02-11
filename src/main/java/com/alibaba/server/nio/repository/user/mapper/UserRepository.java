package com.alibaba.server.nio.repository.user.mapper;

import com.alibaba.server.nio.core.repository.BaseMapperRepository;
import com.alibaba.server.nio.repository.user.repository.dataobject.UserDo;
import com.alibaba.server.nio.repository.user.repository.param.UserDalQueryParam;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户dao
 * @author spring
 * */
@Mapper
public interface UserRepository extends BaseMapperRepository<UserDalQueryParam, UserDo> {

    /**
     * @param param
     * @return
     */
    //String queryPayGroupCode(String param);
}
