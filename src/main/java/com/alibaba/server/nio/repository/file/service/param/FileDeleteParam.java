package com.alibaba.server.nio.repository.file.service.param;

import lombok.Data;

/**
 * 删除文件
 * @author spring
 * */

@Data
public class FileDeleteParam {

    /**
     * 文件id
     */
    private Long id;

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
    private Boolean isFile;

    /**
     * 是否存在
     * */
    private Boolean isExist;

    /**
     * 是否有子文件节点
     */
    private Boolean hasChild;

    /**
     * 所属用户
     * */
    private String userName;
}
