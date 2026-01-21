package com.alibaba.server.nio.service.file.checkpoint;

import com.alibaba.server.nio.model.file.UploadCheckpoint;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 断点管理器（单例）
 * 管理文件上传的断点信息，支持断点续传功能
 * 
 * 存储策略：
 * - 内存存储：使用 ConcurrentHashMap，服务器重启后丢失
 * - 可扩展：可以添加持久化到数据库或文件的功能
 * 
 * @author YSFY
 */
@Slf4j
public class CheckpointManager {
    
    /**
     * 断点信息存储：MD5 -> UploadCheckpoint
     * 使用 MD5 作为 key，确保同一文件在不同连接下能够续传
     */
    private static final ConcurrentHashMap<String, UploadCheckpoint> checkpoints = new ConcurrentHashMap<>();
    
    /**
     * 私有构造函数，防止实例化
     */
    private CheckpointManager() {
    }
    
    /**
     * 保存断点信息
     * 
     * @param checkpoint 断点信息
     */
    public static void saveCheckpoint(UploadCheckpoint checkpoint) {
        if (checkpoint == null || checkpoint.getMd5() == null) {
            log.warn("无效的断点信息，无法保存");
            return;
        }
        
        checkpoint.setUpdateTime(LocalDateTime.now());
        if (checkpoint.getCreateTime() == null) {
            checkpoint.setCreateTime(LocalDateTime.now());
        }
        
        checkpoints.put(checkpoint.getMd5(), checkpoint);
        
        log.info("保存断点 - MD5: {}, 文件: {}, 进度: {}/{} ({:.2f}%)",
                checkpoint.getMd5(),
                checkpoint.getFileName(),
                checkpoint.getUploadedSize(),
                checkpoint.getFileSize(),
                checkpoint.getProgress());
    }
    
    /**
     * 获取断点信息
     * 
     * @param md5 文件MD5值
     * @return 断点信息，如果不存在则返回 null
     */
    public static UploadCheckpoint getCheckpoint(String md5) {
        if (md5 == null) {
            return null;
        }
        return checkpoints.get(md5);
    }
    
    /**
     * 删除断点信息（上传完成后调用）
     * 
     * @param md5 文件MD5值
     * @return 被删除的断点信息，如果不存在则返回 null
     */
    public static UploadCheckpoint removeCheckpoint(String md5) {
        if (md5 == null) {
            return null;
        }
        
        UploadCheckpoint removed = checkpoints.remove(md5);
        if (removed != null) {
            log.info("删除断点 - MD5: {}, 文件: {}", md5, removed.getFileName());
        }
        return removed;
    }
    
    /**
     * 更新断点信息（更新已上传大小）
     * 
     * @param md5 文件MD5值
     * @param uploadedSize 已上传字节数
     */
    public static void updateCheckpoint(String md5, long uploadedSize) {
        if (md5 == null) {
            return;
        }
        
        UploadCheckpoint checkpoint = checkpoints.get(md5);
        if (checkpoint != null) {
            checkpoint.setUploadedSize(uploadedSize);
            checkpoint.setUpdateTime(LocalDateTime.now());
        }
    }
    
    /**
     * 清理过期的断点信息
     * 默认24小时未完成的上传视为过期
     * 
     * @return 清理的断点数量
     */
    public static int cleanExpired() {
        int count = 0;
        
        for (Map.Entry<String, UploadCheckpoint> entry : checkpoints.entrySet()) {
            UploadCheckpoint checkpoint = entry.getValue();
            
            if (checkpoint.isExpired()) {
                // 删除断点记录
                checkpoints.remove(entry.getKey());
                
                // 删除未完成的文件
                if (checkpoint.getFilePath() != null) {
                    File file = new File(checkpoint.getFilePath());
                    if (file.exists() && file.delete()) {
                        log.info("删除过期文件: {}", checkpoint.getFilePath());
                    }
                }
                
                count++;
                log.info("清理过期断点 - MD5: {}, 文件: {}, 已过期: {} 小时",
                        entry.getKey(),
                        checkpoint.getFileName(),
                        java.time.Duration.between(checkpoint.getUpdateTime(), LocalDateTime.now()).toHours());
            }
        }
        
        if (count > 0) {
            log.info("清理过期断点完成，共清理 {} 个", count);
        }
        
        return count;
    }
    
    /**
     * 获取当前断点数量
     */
    public static int getCheckpointCount() {
        return checkpoints.size();
    }
    
    /**
     * 清空所有断点（谨慎使用）
     */
    public static void clearAll() {
        int count = checkpoints.size();
        checkpoints.clear();
        log.warn("清空所有断点信息，共清空 {} 个", count);
    }
    
    /**
     * 检查断点是否存在
     */
    public static boolean hasCheckpoint(String md5) {
        return md5 != null && checkpoints.containsKey(md5);
    }
}
