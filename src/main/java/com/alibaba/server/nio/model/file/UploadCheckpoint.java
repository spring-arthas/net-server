package com.alibaba.server.nio.model.file;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 上传断点检查点实体
 * 用于记录文件上传的断点信息，支持断点续传
 * 
 * @author YSFY
 */
@Data
public class UploadCheckpoint {
    
    /**
     * 文件MD5值（唯一标识，用于断点续传匹配）
     */
    private String md5;
    
    /**
     * 文件名称
     */
    private String fileName;
    
    /**
     * 文件总大小（字节）
     */
    private long fileSize;
    
    /**
     * 已上传大小（字节）
     */
    private long uploadedSize;
    
    /**
     * 服务端存储路径
     */
    private String filePath;
    
    /**
     * 数据库记录ID（用于关联文件表）
     */
    private Long fileId;
    
    /**
     * 创建时间
     */
    private LocalDateTime createTime;
    
    /**
     * 最后更新时间
     */
    private LocalDateTime updateTime;
    
    /**
     * 用户ID
     */
    private Long userId;
    
    /**
     * 目录ID
     */
    private Long dirId;
    
    /**
     * 计算上传进度（百分比）
     */
    public double getProgress() {
        if (fileSize == 0) {
            return 100.0;
        }
        return (uploadedSize * 100.0) / fileSize;
    }
    
    /**
     * 是否已完成
     */
    public boolean isCompleted() {
        return uploadedSize >= fileSize;
    }
    
    /**
     * 检查是否过期（24小时未完成视为过期）
     */
    public boolean isExpired() {
        if (updateTime == null) {
            return false;
        }
        return LocalDateTime.now().isAfter(updateTime.plusHours(24));
    }
}
