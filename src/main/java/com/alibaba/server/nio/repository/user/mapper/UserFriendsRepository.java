package com.alibaba.server.nio.repository.user.mapper;

import com.alibaba.server.nio.core.repository.BaseMapperRepository;
import com.alibaba.server.nio.repository.user.repository.dataobject.UserFriendsDo;
import com.alibaba.server.nio.repository.user.repository.param.UserFriendsDalQueryParam;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 用户好友关系 dao
 * 
 * @author spring
 */
@Mapper
public interface UserFriendsRepository extends BaseMapperRepository<UserFriendsDalQueryParam, UserFriendsDo> {

    @Select("SELECT COUNT(1) FROM user_friends WHERE user_id = #{userId} AND friend_id = #{friendId} AND del = 'N'")
    int countActive(@Param("userId") Integer userId, @Param("friendId") Integer friendId);

    @Select("SELECT COUNT(1) FROM user_friends WHERE del = 'N' AND "
            + "((user_id = #{userId} AND friend_id = #{friendId}) "
            + "OR (user_id = #{friendId} AND friend_id = #{userId}))")
    int countActiveEitherDirection(@Param("userId") Integer userId, @Param("friendId") Integer friendId);

    @Insert("INSERT INTO user_friends(user_id, friend_id, alias, del, del_time, gmt_created, gmt_modified) "
            + "VALUES(#{userId}, #{friendId}, #{alias}, 'N', NULL, NOW(), NOW()) "
            + "ON DUPLICATE KEY UPDATE alias = COALESCE(VALUES(alias), alias), del = 'N', "
            + "del_time = NULL, gmt_modified = NOW()")
    int upsertActiveFriend(@Param("userId") Integer userId,
            @Param("friendId") Integer friendId,
            @Param("alias") String alias);

    @Update("UPDATE user_friends SET alias = #{alias}, gmt_modified = NOW() "
            + "WHERE id = #{id} AND user_id = #{userId} AND del = 'N'")
    int updateOwnedAlias(@Param("id") Long id,
            @Param("userId") Integer userId,
            @Param("alias") String alias);
}
