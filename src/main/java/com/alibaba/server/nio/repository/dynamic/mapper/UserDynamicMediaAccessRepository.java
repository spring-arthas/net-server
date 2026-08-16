package com.alibaba.server.nio.repository.dynamic.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.alibaba.server.nio.repository.file.repository.dataobject.FileDo;

import java.util.List;

/** 批量验证动态附件是否属于当前用户或其可见聊天。 */
@Mapper
public interface UserDynamicMediaAccessRepository {

    @Select({"<script>",
            "SELECT DISTINCT f.id FROM file f",
            "WHERE f.del = 'N' AND f.is_file = 'Y' AND f.is_exist = 'Y' AND f.id IN",
            "<foreach collection=\"fileIds\" item=\"fileId\" open=\"(\" separator=\",\" close=\")\">",
            "#{fileId}",
            "</foreach>",
            "AND (f.user_id = #{userId} OR EXISTS (",
            " SELECT 1 FROM user_friend_message m",
            " WHERE m.del = 'N' AND (m.sender_id = #{userId} OR m.receiver_id = #{userId})",
            " AND m.content REGEXP CONCAT('\\\"(fileId|previewFileId|thumbnailFileId)\\\"[[:space:]]*:[[:space:]]*',",
            " f.id, '([^0-9]|$)')",
            "))",
            "</script>"})
    List<Long> selectAccessibleFileIds(@Param("userId") Long userId,
            @Param("fileIds") List<Long> fileIds);

    @Select({"<script>",
            "SELECT DISTINCT f.id, f.file_name, f.file_type, f.file_size FROM file f",
            "WHERE f.del = 'N' AND f.is_file = 'Y' AND f.is_exist = 'Y' AND f.id IN",
            "<foreach collection=\"fileIds\" item=\"fileId\" open=\"(\" separator=\",\" close=\")\">",
            "#{fileId}",
            "</foreach>",
            "AND (f.user_id = #{userId} OR EXISTS (",
            " SELECT 1 FROM user_friend_message m",
            " WHERE m.del = 'N' AND (m.sender_id = #{userId} OR m.receiver_id = #{userId})",
            " AND m.content REGEXP CONCAT('\\\"(fileId|previewFileId|thumbnailFileId)\\\"[[:space:]]*:[[:space:]]*',",
            " f.id, '([^0-9]|$)')",
            "))",
            "</script>"})
    List<FileDo> selectAccessibleFiles(@Param("userId") Long userId,
            @Param("fileIds") List<Long> fileIds);
}
