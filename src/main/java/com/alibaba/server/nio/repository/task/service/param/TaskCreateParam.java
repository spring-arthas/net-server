package com.alibaba.server.nio.repository.task.service.param;

import lombok.Data;

/**
 * 创建任务
 *
 * @author spring
 */

@Data
public class TaskCreateParam {

    private String taskName;

    private String status;

    private String sponsorUser;

    private String receiveUser;

    private String fileName;

    private Long fileSize;

    private String filePath;
}
