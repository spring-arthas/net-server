package com.alibaba.server.nio.repository.file.service.param;

import lombok.Data;

/**
 * 创建文件
 * @author spring
 * */

@Data
public class FileCreateParam {

    /**
     * 父id
     * */
    private Long pId;

    /**
     * 文件名
     */
    private String fileName;

    /**
     * 文件路径
     */
    private String filePath;

    /**
     * 文件类型
     */
    private String fileType;

    /**
     * 文件大小
     */
    private Long fileSize;

    /**
     * 是否是文件
     */
    private String isFile;

    /**
     * 是否存在
     * */
    private String isExist;

    /**
     * 是否有子文件节点
     */
    private String hasChild;

    /**
     * 所属用户
     * */
    private String userName;

    /**
     * 所属用户ID
     */
    private Long userId;
}
