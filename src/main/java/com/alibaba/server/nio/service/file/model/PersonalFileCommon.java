package com.alibaba.server.nio.service.file.model;

import lombok.Data;

/**
 * 个人网盘文件下载或删除解析对应实体类
 * * @author spring
 * */
@Data
public class PersonalFileCommon {

    /**
     * 操作人
     * */
    private String userName;

    /**
     * 所属文件夹Id
     * */
    private Long folderId;

    /**
     * 待操作的文件名称集合
     * */
    //private String fileNames;

    /**
     * 待操作的文件名称集合对应的文件路径
     * */
    private String filePaths;

    /**
     * 待操作的文件名称id集合
     * */
    private String tags;

    /**
     * 当前文件需要执行的操作
     * */
    private String operate;
}
