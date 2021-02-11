package com.alibaba.server.nio.repository.task.mapper;

import com.alibaba.server.nio.core.repository.BaseMapperRepository;
import com.alibaba.server.nio.repository.task.repository.dataobject.TaskDo;
import com.alibaba.server.nio.repository.task.repository.param.TaskDalQueryParam;
import org.apache.ibatis.annotations.Mapper;

/**
 * 任务dao
 *
 * @author spring
 * */
@Mapper
public interface TaskRepository extends BaseMapperRepository<TaskDalQueryParam, TaskDo> {
}
