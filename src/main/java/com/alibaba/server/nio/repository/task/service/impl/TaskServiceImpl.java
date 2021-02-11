package com.alibaba.server.nio.repository.task.service.impl;

import com.alibaba.server.nio.repository.task.mapper.TaskRepository;
import com.alibaba.server.nio.repository.task.repository.dataobject.TaskDo;
import com.alibaba.server.nio.repository.task.service.TaskService;
import com.alibaba.server.nio.repository.task.service.dto.TaskDto;
import com.alibaba.server.nio.repository.task.service.param.TaskCreateParam;
import com.alibaba.server.nio.repository.task.service.param.TaskUpdateParam;
import com.alibaba.server.nio.repository.user.repository.dataobject.UserDo;
import com.alibaba.server.nio.repository.user.service.dto.UserDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 任务服务
 *
 * @author spring
 * */

@Slf4j
@Service
@SuppressWarnings("all")
public class TaskServiceImpl implements TaskService {

    @Autowired
    private TaskRepository taskRepository;

    @Override
    public TaskDto create(TaskCreateParam param) {
        TaskDo taskDo = this.createParamToDo(param);
        this.taskRepository.insertSelective(taskDo);
        return this.doToDto(taskDo);
    }

    private TaskDo createParamToDo(TaskCreateParam param) {
        TaskDo taskDo = new TaskDo();
        taskDo.setTaskName(param.getTaskName());
        taskDo.setStatus(param.getStatus());
        taskDo.setSponsorUser(param.getSponsorUser());
        taskDo.setReceiveUser(param.getReceiveUser());
        taskDo.setFileName(param.getFileName());
        taskDo.setFileSize(param.getFileSize());
        taskDo.setFilePath(param.getFilePath());
        return taskDo;
    }

    private TaskDto doToDto(TaskDo taskDo) {
        TaskDto taskDto = new TaskDto();
        taskDto.setId(taskDo.getId());
        taskDto.setTaskName(taskDo.getTaskName());
        taskDto.setStatus(taskDo.getStatus());
        taskDto.setSponsorUser(taskDo.getSponsorUser());
        taskDto.setReceiveUser(taskDo.getReceiveUser());
        taskDto.setFileName(taskDo.getFileName());
        taskDto.setFileSize(taskDo.getFileSize());
        taskDto.setFilePath(taskDo.getFilePath());
        return taskDto;
    }

    @Override
    public void update(TaskUpdateParam taskUpdateParam) {

    }
}
