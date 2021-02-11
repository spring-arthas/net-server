package com.alibaba.server.nio.service.file.model;

import lombok.Data;

/**
 * 个人网盘刷新帧
 * @author spring
 * */
@Data
public class PersonalStoreCommon {

    /**
     * 当前文件夹id
     * */
    private Long id;

    /**
     * 父文件夹id
     * */
    private Long pId;

    /**
     * 新文件夹名称
     * */
    private String newFileName;

    /**
     * 创建路径
     * */
    private String filePath;

    /**
     * 文件大小
     * */
    private Long fileSize;

    /**
     * 原始文件名称
     * */
    private String originFileName;

    /**
     * 操作用户
     * */
    private String userName;
}
