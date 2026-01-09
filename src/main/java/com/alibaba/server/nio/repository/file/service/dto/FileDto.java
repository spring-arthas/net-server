package com.alibaba.server.nio.repository.file.service.dto;

import com.alibaba.server.nio.core.service.BaseDTO;
import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * 文件 Dto
 * 
 * @author spring
 */
@Data
public class FileDto extends BaseDTO {

    /**
     * 父id
     */
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
     * 文件大小
     */
    private Long fileSize;

    /**
     * 文件类型
     */
    private String fileType;

    /**
     * 是否是文件
     */
    private String isFile;

    /**
     * 是否存在
     */
    private String isExist;

    /**
     * 是否有子文件节点
     */
    private String hasChild;

    /**
     * 如果hasChild为true，则将字节点文件信息最佳到该List中
     */
    private List<FileDto> childFileList;

    /**
     * 所属用户
     */
    private String userName;

    /**
     * 是否重复创建
     */
    private String repeatCreate;

    /**
     * 当前文件夹下的文件总数量
     */
    private Long fileCount;

    /**
     * 所属用户ID
     */
    private Long userId;

    /**
     * 所属目录名称（用于文件详情展示）
     */
    private String parentDirName;
}
