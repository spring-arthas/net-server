package com.alibaba.server.nio.repository.task.repository.dataobject;

import com.alibaba.server.nio.core.dataobject.BaseDO;
import com.alibaba.server.nio.core.facade.CloneableSupport;
import com.alibaba.server.nio.core.facade.Identity;
import lombok.Data;

/**
 * 任务 Dao
 *
 * @author spring
 */
@Data
public class TaskDo extends BaseDO implements Identity, CloneableSupport {

    private String taskName;

    private String status;

    private String sponsorUser;

    private String receiveUser;

    private String fileName;

    private Long fileSize;

    private String filePath;
}
