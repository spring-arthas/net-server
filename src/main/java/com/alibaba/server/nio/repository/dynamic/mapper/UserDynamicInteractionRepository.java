package com.alibaba.server.nio.repository.dynamic.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 动态互动 Mapper。 */
@Mapper
public interface UserDynamicInteractionRepository {

    @Insert("INSERT INTO user_dynamic_interaction(dynamic_id, user_id, action_type, content, "
            + "idempotency_key, del, del_time, gmt_created, gmt_modified) "
            + "VALUES(#{dynamicId}, #{userId}, #{actionType}, #{content}, #{idempotencyKey}, "
            + "'N', NULL, NOW(3), NOW(3)) ON DUPLICATE KEY UPDATE del = 'N', del_time = NULL, "
            + "content = VALUES(content), gmt_modified = NOW(3)")
    int upsertActive(UserDynamicInteractionDO interaction);

    @Update("UPDATE user_dynamic_interaction SET del = 'Y', del_time = NOW(3), "
            + "gmt_modified = NOW(3) WHERE dynamic_id = #{dynamicId} AND user_id = #{userId} "
            + "AND action_type = #{actionType} AND del = 'N'")
    int deactivate(@Param("dynamicId") Long dynamicId, @Param("userId") Long userId,
            @Param("actionType") String actionType);

    @Select("SELECT COUNT(1) FROM user_dynamic_interaction WHERE dynamic_id = #{dynamicId} "
            + "AND action_type = #{actionType} AND del = 'N'")
    int countActive(@Param("dynamicId") Long dynamicId, @Param("actionType") String actionType);

    @Select("SELECT COUNT(1) FROM user_dynamic_interaction WHERE dynamic_id = #{dynamicId} "
            + "AND user_id = #{userId} AND action_type = #{actionType} AND del = 'N'")
    int existsActive(@Param("dynamicId") Long dynamicId, @Param("userId") Long userId,
            @Param("actionType") String actionType);

    @Select({"<script>",
            "SELECT i.id, i.dynamic_id, i.user_id, i.action_type, i.content, i.idempotency_key,",
            "i.del, i.del_time, i.gmt_created, i.gmt_modified,",
            "u.user_name, u.nick_name, u.avatar",
            "FROM user_dynamic_interaction i",
            "JOIN `user` u ON u.id = i.user_id AND u.del = 'N'",
            "WHERE i.dynamic_id = #{dynamicId} AND i.action_type = 'REPLY' AND i.del = 'N'",
            "<if test=\"beforeId != null\"> AND i.id &lt; #{beforeId}</if>",
            "ORDER BY i.id DESC LIMIT #{limit}",
            "</script>"})
    List<UserDynamicInteractionDO> selectReplies(@Param("dynamicId") Long dynamicId,
            @Param("beforeId") Long beforeId, @Param("limit") int limit);
}
