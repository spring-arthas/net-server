package com.alibaba.server.nio.repository.file.service.param;

import com.alibaba.server.nio.core.param.PageQueryParam;
import lombok.Data;

/**
 * 查询文件
 * 
 * @author spring
 */

@Data
public class FileQueryParam extends PageQueryParam {

    private Long id;

    /**
     * 父节点
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
     * 所属用户
     */
    private String userName;

    /**
     * 所属用户ID
     */
    private Integer userId;

    private String del;
}
