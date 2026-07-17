package com.alibaba.server.nio.repository.file.repository.dataobject;

import com.alibaba.server.nio.core.annotation.Column;
import com.alibaba.server.nio.core.dataobject.BaseDO;
import lombok.Data;

import java.util.Date;

/**
 * 文件任务 Do
 * 
 * @author spring
 */
@Data
public class FileTaskDo extends BaseDO {

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
     * 用户Id
     */
    private Integer userId;

    /**
     * 用户名
     */
    private String userName;

    /**
     * 当前已传输字节偏移量
     */
    private Long currentOffset;

    /**
     * 文件MD5校验码
     */
    @Column(value = "md5")
    private String md5;

    /**
     * 最后活跃时间
     */
    private Date lastActiveTime;
}
