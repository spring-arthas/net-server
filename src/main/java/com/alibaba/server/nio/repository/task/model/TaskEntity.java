package com.alibaba.server.nio.repository.task.model;

import lombok.Data;

import java.util.Date;

/**
 * 任务实体类
 *
 * @author spring
 * */

@Data
public class TaskEntity {

    private String taskName;

    private String status;

    private String sponsorUser;

    private String receiveUser;

    private String fileName;

    private Long fileSize;

    private String filePath;
}
