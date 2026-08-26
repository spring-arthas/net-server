package com.alibaba.server.nio.repository.file.repository.param;

import com.alibaba.server.nio.core.annotation.Column;
import com.alibaba.server.nio.core.annotation.QueryOperator;
import com.alibaba.server.nio.core.param.DalPageQueryParam;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 文件任务查询 dal param
 * 
 * @author spring
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class FileTaskDalQueryParam extends DalPageQueryParam {

    /**
     * 父id
     */
    @Column(value = "parent_id")
    private Long parentId;

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
     * 传输状态
     */
    /**
     * 传输状态
     */
    private Integer status;

    /**
     * MD5
     */
    private String md5;

    /**
     * 用户ID
     */
    private Integer userId;
}
