package com.alibaba.server.nio.repository.file.repository.dataobject;

import lombok.Data;

/**
 * 云盘存储统计结果
 */
@Data
public class DriveStatsDo {
    /**
     * 目录总数
     */
    private Integer totalDirectories;
    /**
     * 文件总数
     */
    private Long totalFiles;
    /**
     * 已使用空间（字节）
     */
    private Long totalBytes;
}
