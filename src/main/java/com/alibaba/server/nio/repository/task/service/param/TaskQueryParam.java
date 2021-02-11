package com.alibaba.server.nio.repository.task.service.param;

import com.alibaba.server.nio.core.param.PageQueryParam;
import lombok.Data;

/**
 * 任务查询
 *
 * @author spring
 */

@Data
public class TaskQueryParam extends PageQueryParam {

    private String taskName;

    private String status;

    private String sponsorUser;

    private String receiveUser;

    private String fileName;

    private Long fileSize;

    private String filePath;
}
