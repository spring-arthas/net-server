package com.alibaba.server.nio.repository.file.mapper;

import com.alibaba.server.nio.core.repository.BaseMapperRepository;
import com.alibaba.server.nio.repository.file.repository.dataobject.DriveStatsDo;
import com.alibaba.server.nio.repository.file.repository.dataobject.FileDo;
import com.alibaba.server.nio.repository.file.repository.param.FileDalQueryParam;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/**
 * 文件dao
 * 
 * @author spring
 */
@Mapper
public interface FileRepository extends BaseMapperRepository<FileDalQueryParam, FileDo> {

    /**
     * 查询指定文件信息
     * 
     * @param fileDalQueryParam
     * @return
     */
    List<FileDo> getAssignFiles(FileDalQueryParam fileDalQueryParam);

    /**
     * 查询当前文件夹下的文件
     * 
     * @param fileDalQueryParam
     * @return
     */
    List<FileDo> queryFilesBelongCurrentFolder(FileDalQueryParam fileDalQueryParam);

    /**
     * 手动分页查询
     * 
     * @param fileDalQueryParam
     * @return
     */
    /**
     * 手动分页查询
     * 
     * @param fileDalQueryParam
     * @return
     */
    List<FileDo> page(FileDalQueryParam fileDalQueryParam);

    /**
     * 手动查询总数
     * 
     * @param fileDalQueryParam
     * @return
     */
    long count(FileDalQueryParam fileDalQueryParam);

    /**
     * 聚合查询用户云盘统计：目录总数、文件总数、已使用空间
     *
     * @param userId 用户ID
     * @return 统计结果
     */
    DriveStatsDo getDriveStats(@Param("userId") Integer userId);
}
