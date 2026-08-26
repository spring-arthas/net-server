package com.alibaba.server.nio.repository.file.service.param;

import com.alibaba.server.nio.core.param.PageQueryParam;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 文件任务查询 param
 * 
 * @author spring
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class FileTaskQueryParam extends PageQueryParam {

    private Long id;

    /**
     * 父节点Id
     */
    private Long parentId;

    /**
     * 文件名
     */
    private String fileName;

    /**
     * 传输状态
     */
    private Integer status;

    private String del;
}
