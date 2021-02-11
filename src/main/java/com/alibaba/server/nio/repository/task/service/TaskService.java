package com.alibaba.server.nio.repository.task.service;

import com.alibaba.server.nio.repository.task.service.dto.TaskDto;
import com.alibaba.server.nio.repository.task.service.param.TaskCreateParam;
import com.alibaba.server.nio.repository.task.service.param.TaskUpdateParam;
import com.alibaba.server.nio.repository.user.service.dto.UserDTO;
import com.alibaba.server.nio.repository.user.service.param.UserCreateParam;
import com.alibaba.server.nio.repository.user.service.param.UserQueryParam;
import com.alibaba.server.nio.repository.user.service.param.UserUpdateParam;

/**
 * 任务服务
 *
 * @author spring
 * */
public interface TaskService {

    /**
     * 创建任务
     *
     * @param taskCreateParam
     * @return TaskDto
     */
    TaskDto create(TaskCreateParam taskCreateParam);

    /**
     * 更新任务
     *
     * @param taskUpdateParam
     * @return userDto
     */
    void update(TaskUpdateParam taskUpdateParam);
}
