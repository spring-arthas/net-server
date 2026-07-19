package com.alibaba.server.nio.repository.user.mapper;

import com.alibaba.server.nio.core.repository.BaseMapperRepository;
import com.alibaba.server.nio.repository.user.repository.dataobject.UserDo;
import com.alibaba.server.nio.repository.user.repository.param.UserDalQueryParam;
import com.alibaba.server.nio.repository.user.service.dto.UserSearchDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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

    /** 好友搜索专用查询，只返回公开字段。 */
    List<UserSearchDTO> searchUsers(@Param("keyword") String keyword,
            @Param("likeKeyword") String likeKeyword);

    /** 查询有效用户是否存在。 */
    @Select("SELECT COUNT(1) FROM user WHERE id = #{userId} AND del = 'N'")
    int countActiveById(@Param("userId") Long userId);
}
