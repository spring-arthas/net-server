package com.alibaba.server.nio.repository.task.service.dto;

import com.alibaba.server.nio.core.service.BaseDTO;
import lombok.Data;

@Data
public class TaskDto extends BaseDTO {

    private String taskName;

    private String status;

    private String sponsorUser;

    private String receiveUser;

    private String fileName;

    private Long fileSize;

    private String filePath;
}
