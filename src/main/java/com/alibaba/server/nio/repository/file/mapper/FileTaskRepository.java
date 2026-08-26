package com.alibaba.server.nio.repository.file.mapper;

import com.alibaba.server.nio.core.repository.BaseMapperRepository;
import com.alibaba.server.nio.repository.file.repository.dataobject.FileTaskDo;
import com.alibaba.server.nio.repository.file.repository.param.FileTaskDalQueryParam;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 文件任务 dao
 * 
 * @author spring
 */
@Mapper
public interface FileTaskRepository extends BaseMapperRepository<FileTaskDalQueryParam, FileTaskDo> {

    /**
     * 分页查询文件任务
     * 
     * @param param
     * @return
     */
    List<FileTaskDo> page(FileTaskDalQueryParam param);

    /**
     * 查询文件任务总数
     * 
     * @param param
     * @return
     */
    long count(FileTaskDalQueryParam param);
}
