package com.alibaba.server.nio.repository.file.service.dto;

import com.alibaba.server.nio.core.service.BaseDTO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;

/**
 * 文件任务 Dto
 * 
 * @author spring
 */
@Data
public class FileTaskDto {

    /**
     * 所属目录Id
     */
    private Long parentId;

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
     * 文件大小（字节）
     */
    private Long fileSize;

    /**
     * 文件任务传输状态：0=待上传，1=上传中，2=上传完成，3=上传失败；4=待下载，5=下载中，6=下载完成，7=下载失败
     */
    private Integer status;

    /**
     * 主键
     */
    private Long id;

    /**
     * 用户Id
     * */
    private Integer userId;

    /**
     * 用户名
     * */
    private String userName;

    /**
     * 创建时间
     */
    private Date gmtCreated;

    /**
     * 修改时间
     */
    private Date gmtModified;

    /**
     * 是否删除：0=否，1=是
     */
    private String del;
}
