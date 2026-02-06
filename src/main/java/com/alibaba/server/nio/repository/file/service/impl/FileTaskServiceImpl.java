package com.alibaba.server.nio.repository.file.service.impl;

import com.alibaba.server.common.SnowflakeIdWorkerUtil;
import com.alibaba.server.common.SnowflakeIdWorkerUtil;
import com.alibaba.server.nio.core.result.PageResult;
import com.alibaba.server.nio.repository.file.mapper.FileTaskRepository;
import com.alibaba.server.nio.repository.file.repository.dataobject.FileTaskDo;
import com.alibaba.server.nio.repository.file.repository.param.FileTaskDalQueryParam;
import com.alibaba.server.nio.repository.file.service.FileTaskService;
import com.alibaba.server.nio.repository.file.service.dto.FileTaskDto;
import com.alibaba.server.nio.repository.file.service.param.FileTaskQueryParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 文件任务服务实现类
 * 
 * @author spring
 */
@Slf4j
@Service
public class FileTaskServiceImpl implements FileTaskService {

    @Autowired
    private FileTaskRepository fileTaskRepository;

    @Transactional(rollbackFor = Throwable.class)
    @Override
    public FileTaskDto create(FileTaskDto fileTaskDto) {
        FileTaskDo fileTaskDo = this.dtoToDo(fileTaskDto);
        fileTaskDo.setId(SnowflakeIdWorkerUtil.generateId());
        // 根据表结构截图，is_deleted 为 int 类型，0=否，1=是
        fileTaskDo.setIsDeleted(0);
        Date date = new Date();
        fileTaskDo.setGmtCreated(date);
        fileTaskDo.setGmtModified(date);
        fileTaskRepository.insertSelective(fileTaskDo);
        return this.doToDto(fileTaskDo);
    }
    @Transactional(rollbackFor = Throwable.class)
    @Override
    public FileTaskDto update(FileTaskDto fileTaskDto) {
        if (Objects.isNull(fileTaskDto.getId())) {
            throw new IllegalArgumentException("任务Id不能为空");
        }
        FileTaskDo fileTaskDo = this.dtoToDo(fileTaskDto);
        fileTaskDo.setGmtModified(new Date());

        fileTaskRepository.updateSelective(fileTaskDo);
        return this.doToDto(fileTaskRepository.get(fileTaskDto.getId()));
    }

    @Override
    public FileTaskDto getById(Long id) {
        FileTaskDo fileTaskDo = fileTaskRepository.get(id);
        return Objects.nonNull(fileTaskDo) ? this.doToDto(fileTaskDo) : null;
    }

    @Override
    public PageResult<FileTaskDto> queryPage(FileTaskQueryParam param) {
        FileTaskDalQueryParam dalParam = new FileTaskDalQueryParam();
        dalParam.setParentId(param.getParentId());
        dalParam.setFileName(param.getFileName());
        dalParam.setStatus(param.getStatus());
        dalParam.setDel(param.getDel() == null ? "0" : param.getDel());
        dalParam.setCurrentPage(param.getCurrentPage());
        dalParam.setPageSize(param.getPageSize());

        long count = fileTaskRepository.count(dalParam);
        PageResult<FileTaskDto> result = new PageResult<>();
        result.setTotalCount((int) count);
        result.setCurrentPage(param.getCurrentPage());
        result.setPageSize(param.getPageSize());

        if (count > 0) {
            List<FileTaskDo> list = fileTaskRepository.page(dalParam);
            result.setModelList(list.stream().map(this::doToDto).collect(Collectors.toList()));
        }
        return result;
    }
    @Transactional(rollbackFor = Throwable.class)
    @Override
    public Boolean deleteById(Long id) {
        if (Objects.isNull(id)) {
            return false;
        }
        fileTaskRepository.delete(id);
        return true;
    }

    private FileTaskDo dtoToDo(FileTaskDto dto) {
        FileTaskDo fileTaskDo = new FileTaskDo();
        fileTaskDo.setId(dto.getId());
        fileTaskDo.setParentId(dto.getParentId());
        fileTaskDo.setFileName(dto.getFileName());
        fileTaskDo.setFilePath(dto.getFilePath());
        fileTaskDo.setFileType(dto.getFileType());
        fileTaskDo.setFileSize(dto.getFileSize());
        fileTaskDo.setStatus(dto.getStatus());
        fileTaskDo.setUserId(dto.getUserId());
        fileTaskDo.setUserName(dto.getUserName());
        fileTaskDo.setIsDeleted(dto.getIsDeleted());
        fileTaskDo.setGmtCreated(dto.getGmtCreated());
        fileTaskDo.setGmtModified(dto.getGmtModified());
        return fileTaskDo;
    }

    private FileTaskDto doToDto(FileTaskDo fileTaskDo) {
        FileTaskDto dto = new FileTaskDto();
        dto.setId(fileTaskDo.getId());
        dto.setParentId(fileTaskDo.getParentId());
        dto.setFileName(fileTaskDo.getFileName());
        dto.setFilePath(fileTaskDo.getFilePath());
        dto.setFileType(fileTaskDo.getFileType());
        dto.setFileSize(fileTaskDo.getFileSize());
        dto.setStatus(fileTaskDo.getStatus());
        dto.setUserId(fileTaskDo.getUserId());
        dto.setUserName(fileTaskDo.getUserName());
        dto.setIsDeleted(fileTaskDo.getIsDeleted());
        dto.setGmtCreated(fileTaskDo.getGmtCreated());
        dto.setGmtModified(fileTaskDo.getGmtModified());
        return dto;
    }
}
