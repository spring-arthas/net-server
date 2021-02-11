package com.alibaba.server.nio.service.file.model;

import lombok.Data;

/**
 * 个人网盘刷新帧
 * @author spring
 * */
@Data
public class PersonalStoreRefresh {

    private String id;

    private String userName;

    private String fileName;

    private String filePath;

    private Long fileSize;

    private String refreshFile;

    private String pId;

    private Integer currentPage;

    private Integer pageSize;
}
