package com.alibaba.server.nio.service.file.model;

import lombok.Data;

/**
 * 文件帧持有的文件传输对象, 每个文件传输帧中data字段byte[]数组解析而来
 * @author spring
 * */
@Data
public class FileTransport {

    /**
     * 发起用户
     * */
    private String launchUserName;

    /**
     * 远程用户
     * */
    private String receiveUserName;

    /**
     * 文件存储二级目录，以分号相隔
     * */
    private String group;

    /**
     * 唯一文件标识
     * */
    private String tag;

    /**
     * 文件名称
     * */
    private String fileName;

    /**
     * 文件数量
     * */
    private Integer fileCount;

    /**
     * 文件总大小
     * */
    private Long fileSize;

    /**
     * 文件路径
     * */
    private String filePath;

    /**
     * 解析结果包含的操作
     * */
    private String operate;

    /**
     * 是否接收在线传输文件，远程用户点击确定值未TRUE，点击取消则未FALSE
     * */
    private Boolean isConfirmReceive;
}
