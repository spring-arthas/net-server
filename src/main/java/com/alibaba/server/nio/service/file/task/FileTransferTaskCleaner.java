package com.alibaba.server.nio.service.file.task;

import com.alibaba.server.nio.service.file.handler.FileDownloadHandler;
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
        } catch (Exception e) {
            log.error("文件传输任务清理失败", e);
        }
    }
}
