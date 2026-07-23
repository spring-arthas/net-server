package com.alibaba.server.nio.repository.user.mapper;

import com.alibaba.server.nio.core.repository.BaseMapperRepository;
import com.alibaba.server.nio.repository.user.repository.dataobject.UserFriendApplyDo;
import com.alibaba.server.nio.repository.user.repository.param.UserFriendApplyDalQueryParam;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 好友添加请求 dao
 * 
 * @author spring
 */
@Mapper
public interface UserFriendApplyRepository
        extends BaseMapperRepository<UserFriendApplyDalQueryParam, UserFriendApplyDo> {

    @Select("SELECT * FROM user_friend_apply WHERE sender_id = #{senderId} AND receiver_id = #{receiverId} "
            + "AND status = 0 AND del = 'N' ORDER BY id DESC LIMIT 1")
    UserFriendApplyDo findPending(@Param("senderId") Integer senderId,
            @Param("receiverId") Integer receiverId);

    @Select("SELECT * FROM user_friend_apply WHERE id = #{requestId} AND receiver_id = #{receiverId} "
            + "AND status = 0 AND del = 'N' FOR UPDATE")
    UserFriendApplyDo findPendingForUpdate(@Param("requestId") Long requestId,
            @Param("receiverId") Integer receiverId);

    @Update("UPDATE user_friend_apply SET status = #{status}, gmt_modified = NOW() "
            + "WHERE id = #{requestId} AND receiver_id = #{receiverId} AND status = 0 AND del = 'N'")
    int updatePendingStatus(@Param("requestId") Long requestId,
            @Param("receiverId") Integer receiverId,
            @Param("status") Integer status);

    @Select("SELECT COUNT(1) FROM user_friend_apply WHERE sender_id = #{senderId} AND del = 'N' "
            + "AND gmt_created >= DATE_SUB(NOW(), INTERVAL #{minutes} MINUTE)")
    int countCreatedSince(@Param("senderId") Integer senderId, @Param("minutes") int minutes);

    @Select("SELECT COUNT(1) FROM user_friend_apply WHERE sender_id = #{senderId} "
            + "AND receiver_id = #{receiverId} AND status = 2 AND del = 'N' "
            + "AND gmt_modified >= DATE_SUB(NOW(), INTERVAL #{minutes} MINUTE)")
    int countRecentRejected(@Param("senderId") Integer senderId,
            @Param("receiverId") Integer receiverId,
            @Param("minutes") int minutes);
}
