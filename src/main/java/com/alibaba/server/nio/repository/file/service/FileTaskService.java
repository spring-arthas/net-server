package com.alibaba.server.nio.repository.file.service;

import com.alibaba.server.nio.core.result.PageResult;
import com.alibaba.server.nio.repository.file.service.dto.FileTaskDto;
import com.alibaba.server.nio.repository.file.service.param.FileTaskQueryParam;

/**
 * 文件任务服务
 * 
 * @author spring
 */
public interface FileTaskService {

    /**
     * 创建文件任务
     * 
     * @param fileTaskDto
     * @return
     */
    FileTaskDto create(FileTaskDto fileTaskDto);

    /**
     * 更新文件任务
     * 
     * @param fileTaskDto
     * @return
     */
    FileTaskDto update(FileTaskDto fileTaskDto);

    /**
     * 根据Id获取文件任务
     * 
     * @param id
     * @return
     */
    FileTaskDto getById(Long id);

    /**
     * 分页查询文件任务
     * 
     * @param param
     * @return
     */
    PageResult<FileTaskDto> queryPage(FileTaskQueryParam param);

    /**
     * 物理删除文件任务
     * 
     * @param id
     * @return
     */
    Boolean deleteById(Long id);
}
