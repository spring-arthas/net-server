package com.alibaba.server.nio.repository.file.repository.param;

import com.alibaba.server.nio.core.annotation.Column;
import com.alibaba.server.nio.core.annotation.QueryOperator;
import com.alibaba.server.nio.core.param.DalPageQueryParam;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 文件查询dal param
 * 
 * @author spring
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class FileDalQueryParam extends DalPageQueryParam {

    /**
     * 父id
     */
    @Column(value = "p_id")
    private Long pId;

    /**
     * 文件名
     */
    @QueryOperator("LIKE")
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
     * 是否存在
     */
    private String isExist;

    /**
     * 是否是文件
     */
    private String isFile;

    /**
     * 是否有子文件节点
     */
    private String hasChild;

    /**
     * 所属用户
     */
    private String userName;

    private Integer userId;
}
