package com.alibaba.server.nio.repository.task.service.param;

import lombok.Data;

/**
 * 编辑任务
 *
 * @author spring
 */

@Data
public class TaskUpdateParam {

    private Long id;

    private String taskName;

    private String status;

    private String sponsorUser;

    private String receiveUser;

    private String fileName;

    private Long fileSize;

    private String filePath;
}
