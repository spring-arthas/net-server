package com.alibaba.server.nio.repository.dynamic.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 用户动态 Mapper。复杂可见性在单条 SQL 内完成，避免循环查库。 */
@Mapper
public interface UserDynamicRepository {

    @Insert("INSERT INTO user_dynamic(user_id, content, image_paths, media_json, reference_json, "
            + "del, del_time, gmt_created, gmt_modified) VALUES(#{userId}, #{content}, #{imagePaths}, "
            + "#{mediaJson}, #{referenceJson}, 'N', NULL, NOW(3), NOW(3))")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insertDynamic(UserDynamicDO dynamic);

    @Select("SELECT d.id, d.user_id, d.content, d.image_paths, d.media_json, d.reference_json, "
            + "d.del, d.del_time, d.gmt_created, d.gmt_modified, "
            + "u.user_name, u.nick_name, u.avatar, 0 AS like_count, 0 AS reply_count, "
            + "0 AS repost_count, FALSE AS liked, FALSE AS reposted "
            + "FROM user_dynamic d JOIN `user` u ON u.id = d.user_id AND u.del = 'N' "
            + "WHERE d.id = #{dynamicId} AND d.user_id = #{userId} AND d.del = 'N'")
    UserDynamicDO selectOwnedById(@Param("userId") Long userId,
            @Param("dynamicId") Long dynamicId);

    @Select({"<script>",
            "SELECT d.id, d.user_id, d.content, d.image_paths, d.media_json, d.reference_json,",
            "d.del, d.del_time, d.gmt_created, d.gmt_modified,",
            "u.user_name, u.nick_name, u.avatar,",
            "COALESCE(c.like_count, 0) AS like_count,",
            "COALESCE(c.reply_count, 0) AS reply_count,",
            "COALESCE(c.repost_count, 0) AS repost_count,",
            "CASE WHEN COALESCE(v.liked, 0) &gt; 0 THEN TRUE ELSE FALSE END AS liked,",
            "CASE WHEN COALESCE(v.reposted, 0) &gt; 0 THEN TRUE ELSE FALSE END AS reposted",
            "FROM user_dynamic d",
            "JOIN `user` u ON u.id = d.user_id AND u.del = 'N'",
            "LEFT JOIN (",
            " SELECT dynamic_id,",
            " SUM(CASE WHEN action_type = 'LIKE' THEN 1 ELSE 0 END) AS like_count,",
            " SUM(CASE WHEN action_type = 'REPLY' THEN 1 ELSE 0 END) AS reply_count,",
            " SUM(CASE WHEN action_type = 'REPOST' THEN 1 ELSE 0 END) AS repost_count",
            " FROM user_dynamic_interaction WHERE del = 'N' GROUP BY dynamic_id",
            ") c ON c.dynamic_id = d.id",
            "LEFT JOIN (",
            " SELECT dynamic_id,",
            " MAX(CASE WHEN action_type = 'LIKE' THEN 1 ELSE 0 END) AS liked,",
            " MAX(CASE WHEN action_type = 'REPOST' THEN 1 ELSE 0 END) AS reposted",
            " FROM user_dynamic_interaction",
            " WHERE user_id = #{userId} AND del = 'N' GROUP BY dynamic_id",
            ") v ON v.dynamic_id = d.id",
            "WHERE d.del = 'N'",
            "<if test=\"beforeId != null\"> AND d.id &lt; #{beforeId}</if>",
            "<choose>",
            " <when test=\"scope == 'MINE'\"> AND d.user_id = #{userId}</when>",
            " <otherwise> AND (d.user_id = #{userId} OR EXISTS (",
            "  SELECT 1 FROM user_friends f WHERE f.user_id = #{userId}",
            "  AND f.friend_id = d.user_id AND f.del = 'N'",
            " ))</otherwise>",
            "</choose>",
            "ORDER BY d.id DESC LIMIT #{limit}",
            "</script>"})
    List<UserDynamicDO> selectVisibleTimeline(@Param("userId") Long userId,
            @Param("scope") String scope, @Param("beforeId") Long beforeId,
            @Param("limit") int limit);

    @Select("SELECT d.id, d.user_id, d.content, d.image_paths, d.media_json, d.reference_json, "
            + "d.del, d.del_time, d.gmt_created, d.gmt_modified, "
            + "u.user_name, u.nick_name, u.avatar, "
            + "COALESCE(c.like_count, 0) AS like_count, COALESCE(c.reply_count, 0) AS reply_count, "
            + "COALESCE(c.repost_count, 0) AS repost_count, "
            + "CASE WHEN COALESCE(v.liked, 0) > 0 THEN TRUE ELSE FALSE END AS liked, "
            + "CASE WHEN COALESCE(v.reposted, 0) > 0 THEN TRUE ELSE FALSE END AS reposted "
            + "FROM user_dynamic d JOIN `user` u ON u.id = d.user_id AND u.del = 'N' "
            + "LEFT JOIN (SELECT dynamic_id, "
            + "SUM(CASE WHEN action_type = 'LIKE' THEN 1 ELSE 0 END) AS like_count, "
            + "SUM(CASE WHEN action_type = 'REPLY' THEN 1 ELSE 0 END) AS reply_count, "
            + "SUM(CASE WHEN action_type = 'REPOST' THEN 1 ELSE 0 END) AS repost_count "
            + "FROM user_dynamic_interaction WHERE del = 'N' GROUP BY dynamic_id) c ON c.dynamic_id = d.id "
            + "LEFT JOIN (SELECT dynamic_id, "
            + "MAX(CASE WHEN action_type = 'LIKE' THEN 1 ELSE 0 END) AS liked, "
            + "MAX(CASE WHEN action_type = 'REPOST' THEN 1 ELSE 0 END) AS reposted "
            + "FROM user_dynamic_interaction WHERE user_id = #{userId} AND del = 'N' "
            + "GROUP BY dynamic_id) v ON v.dynamic_id = d.id "
            + "WHERE d.id = #{dynamicId} AND d.del = 'N' AND (d.user_id = #{userId} OR EXISTS ("
            + "SELECT 1 FROM user_friends f WHERE f.user_id = #{userId} "
            + "AND f.friend_id = d.user_id AND f.del = 'N'))")
    UserDynamicDO selectVisibleById(@Param("userId") Long userId,
            @Param("dynamicId") Long dynamicId);

    @Update("UPDATE user_dynamic SET del = 'Y', del_time = NOW(3), gmt_modified = NOW(3) "
            + "WHERE id = #{dynamicId} AND user_id = #{userId} AND del = 'N'")
    int logicalDeleteOwned(@Param("dynamicId") Long dynamicId, @Param("userId") Long userId);

    @Select("SELECT COUNT(1) FROM user_dynamic d WHERE d.del = 'N' "
            + "AND (d.user_id = #{userId} OR EXISTS (SELECT 1 FROM user_friends f "
            + "WHERE f.user_id = #{userId} AND f.friend_id = d.user_id AND f.del = 'N')) "
            + "AND ((d.media_json IS NOT NULL AND JSON_CONTAINS(d.media_json, "
            + "CAST(#{fileId} AS JSON), '$[*].fileId')) "
            + "OR (d.reference_json IS NOT NULL AND JSON_CONTAINS(d.reference_json, "
            + "CAST(#{fileId} AS JSON), '$.media[*].fileId')) "
            + "OR FIND_IN_SET(CAST(#{fileId} AS CHAR), REPLACE(COALESCE(d.image_paths, ''), ' ', '')) > 0)")
    int countVisibleMediaReferences(@Param("userId") Long userId, @Param("fileId") Long fileId);
}
