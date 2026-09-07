package com.alibaba.server.nio.service.file.task;

import com.alibaba.server.nio.service.file.checkpoint.CheckpointManager;
import com.alibaba.server.nio.service.file.handler.FileDownloadHandler;
import com.alibaba.server.nio.service.file.handler.FileRangePullHandler;
import com.alibaba.server.nio.service.file.handler.FileUploadHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 文件传输任务清理守护进程
 * */
@Slf4j
public class FileTransferTaskCleaner {

    // 5 minutes idle threshold
    private static final long IDLE_THRESHOLD = 5 * 60 * 1000L;

    @Scheduled(fixedRate = 60000) // Run every minute
    public void cleanIdleTasks() {
        log.info("执行文件传输任务清理守护进程...");
        try {
            // Clean uploads
            FileUploadHandler.checkAndFreezeIdleTasks(IDLE_THRESHOLD);

            // Clean downloads
            FileDownloadHandler.checkAndFreezeIdleTasks(IDLE_THRESHOLD);

            // Clean pull-range sessions
            FileRangePullHandler.checkAndFreezeIdleTasks(IDLE_THRESHOLD);

            // [修改] 清理过期上传断点和孤儿部分文件（默认24小时未完成视为过期），
            // 兜底处理服务端重启后内存断点丢失但磁盘文件仍存在的场景。
            // DB 层 PAUSED 记录体积小，暂不纳入定时清理；客户端主动删除时通过 UPLOAD_ABORT 帧实时清理。
            int expiredCount = CheckpointManager.cleanExpired();
            if (expiredCount > 0) {
                log.info("已清理 {} 个过期上传断点及关联部分文件", expiredCount);
            }
        } catch (Exception e) {
            log.error("文件传输任务清理失败", e);
        }
    }
}
