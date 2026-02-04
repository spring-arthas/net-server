package com.alibaba.server.nio.model.file;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 文件上传上下文
 * 保存单次文件上传的状态信息，包括任务ID、文件信息、写入进度等
 * 
 * @author duyao
 */
@Data
@Slf4j
public class FileUploadContext {
    /**
     * 文件存储根目录
     */
    private static final String FILE_STORAGE_ROOT = "/Users/hljy/Downloads/西班牙的荷包蛋/";

    /**
     * 生成唯一任务ID
     */
    public static String generateTaskId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 任务ID（唯一标识一次上传）
     */
    private String taskId;
    /**
     * 文件名称
     */
    private String fileName;
    /**
     * 文件大小（字节）
     */
    private long fileSize;
    /**
     * 文件类型（扩展名）
     */
    private String fileType;
    /**
     * 文件通道
     */
    private FileChannel fileChannel;
    /**
     * 已写入字节数
     */
    private long bytesWritten = 0;
    /**
     * 自定义存储基路径（如果设置，则使用此路径而非默认路径）
     */
    private String basePath;
    /**
     * 文件存储路径
     */
    private String filePath;
    /**
     * 客户端远程地址（用于关联上传上下文与客户端连接）
     */
    private String remoteAddress;
    /**
     * 数据库记录 ID（用于断连时删除记录）
     */
    private Long fileId;
    /**
     * 上传开始时间
     */
    private LocalDateTime startTime;
    /**
     * 是否为断点续传模式
     */
    private boolean isResume = false;

    /**
     * 上传状态
     */
    public enum UploadStatus {
        INITIALIZED, // 已初始化
        UPLOADING, // 上传中
        COMPLETED, // 已完成
        FAILED // 失败
    }

    private UploadStatus status = UploadStatus.INITIALIZED;
    /**
     * 标记是否已获取并发许可（用于释放时判断）
     */
    private boolean semaphoreAcquired = false;
    /**
     * 上次统计时间（用于计算实时速率）
     */
    private long lastStatTime = 0;
    /**
     * 上次统计时的已写入字节数
     */
    private long lastStatBytes = 0;
    /**
     * 当前上传速率（字节/秒）
     */
    private volatile long currentSpeed = 0;
    /**
     * 文件MD5值（用于断点续传唯一标识）
     */
    private String md5;
    /**
     * 起始偏移量（断点续传时非0）
     */
    private long startOffset = 0;

    /**
     * 检查上传是否完成
     */
    public boolean isComplete() {
        return bytesWritten >= fileSize;
    }

    /**
     * 获取上传进度（百分比）
     */
    public double getProgress() {
        if (fileSize == 0) {
            return 100.0;
        }
        return (bytesWritten * 100.0) / fileSize;
    }

    /**
     * 构建文件存储路径
     * 如果设置了 basePath，使用自定义路径
     * 否则使用默认格式：/data/file-storage/yyyy/MM/dd/{taskId}_{fileName}
     */
    public String buildFilePath() {
        if (basePath != null && !basePath.isEmpty()) {
            // 使用自定义目录路径
            this.filePath = basePath + "/" + taskId + "_" + fileName;
        } else {
            // 使用默认路径
            LocalDateTime now = LocalDateTime.now();
            String datePath = now.format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
            this.filePath = FILE_STORAGE_ROOT + datePath + "/" + taskId + "_" + fileName;
        }
        return this.filePath;
    }

    /**
     * 打开文件通道
     * 支持断点续传：根据 isResume 标志选择打开模式
     */
    public FileChannel openFileChannel() throws IOException {
        if (filePath == null) {
            buildFilePath();
        }

        // 确保目录存在
        Path path = Paths.get(filePath);
        Path parentDir = path.getParent();
        if (parentDir != null && !java.nio.file.Files.exists(parentDir)) {
            java.nio.file.Files.createDirectories(parentDir);
        }

        // 根据是否断点续传选择打开模式
        if (isResume && java.nio.file.Files.exists(path)) {
            // 断点续传：追加模式（APPEND）
            this.fileChannel = FileChannel.open(path,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND);

            // 初始化已写入字节数为起始偏移量
            this.bytesWritten = startOffset;

            log.info("断点续传模式 - taskId: {}, 文件: {}, 从 {} 字节继续上传 ({:.2f}%)",
                    taskId, fileName, startOffset, getProgress());
        } else {
            // 全新上传：覆盖模式（TRUNCATE_EXISTING）
            this.fileChannel = FileChannel.open(path,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);

            this.bytesWritten = 0;
            log.info("全新上传模式 - taskId: {}, 文件: {}", taskId, fileName);
        }

        this.status = UploadStatus.UPLOADING;
        this.startTime = LocalDateTime.now();

        log.info("打开文件通道: taskId={}, filePath={}", taskId, filePath);
        return this.fileChannel;
    }

    /**
     * 写入数据
     * 注意：FileChannel.write() 可能不会一次写入所有数据，需要循环确保完整写入
     */
    public int writeData(byte[] data) throws IOException {
        if (fileChannel == null || !fileChannel.isOpen()) {
            throw new IOException("文件通道未打开或已关闭");
        }

        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(data);
        int totalWritten = 0;

        // 循环写入，确保所有数据都被写入文件
        // FileChannel.write() 可能不会一次写入所有数据，特别是在高负载或大数据块时
        while (buffer.hasRemaining()) {
            int written = fileChannel.write(buffer);
            if (written == 0) {
                // 如果写入了0字节，短暂休眠后重试，避免忙等待
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("写入被中断");
                }
            }
            totalWritten += written;
        }

        bytesWritten += totalWritten;

        /*
         * log.debug("写入数据: taskId={}, written={}, total={}/{}",
         * taskId, totalWritten, bytesWritten, fileSize);
         */

        return totalWritten;
    }

    /**
     * 更新上传速率统计
     * 计算当前上传速率（字节/秒）
     */
    public void updateSpeed() {
        long currentTime = System.currentTimeMillis();

        if (lastStatTime == 0) {
            lastStatTime = currentTime;
            lastStatBytes = bytesWritten;
            currentSpeed = 0;
            return;
        }

        long timeDiff = currentTime - lastStatTime;
        if (timeDiff >= 1000) {
            long bytesDiff = bytesWritten - lastStatBytes;
            currentSpeed = (bytesDiff * 1000) / timeDiff;

            lastStatTime = currentTime;
            lastStatBytes = bytesWritten;
        }
    }

    /**
     * 获取当前上传速率（字节/秒）
     */
    public long getCurrentSpeed() {
        return currentSpeed;
    }

    /**
     * 格式化速率显示（KB/s 或 MB/s）
     */
    public String getFormattedSpeed() {
        if (currentSpeed < 1024) {
            return currentSpeed + " B/s";
        } else if (currentSpeed < 1024 * 1024) {
            return String.format("%.1f KB/s", currentSpeed / 1024.0);
        } else {
            return String.format("%.1f MB/s", currentSpeed / 1024.0 / 1024.0);
        }
    }

    /**
     * 关闭文件通道
     */
    public void closeFileChannel() {
        if (fileChannel != null) {
            try {
                if (fileChannel.isOpen()) {
                    fileChannel.force(true); // 强制刷新到磁盘
                    fileChannel.close();
                }
            } catch (IOException e) {
                log.error("关闭文件通道失败: taskId={}", taskId, e);
            }
        }
    }

    /**
     * 标记上传完成
     */
    public void markCompleted() {
        this.status = UploadStatus.COMPLETED;
        closeFileChannel();
        log.info("文件上传完成: taskId={}, fileName={}, size={}，文件通道关闭成功: bytesWritten={}/{}", taskId, fileName, fileSize,
                bytesWritten, fileSize);
    }

    /**
     * 标记上传失败
     */
    public void markFailed(String reason) {
        this.status = UploadStatus.FAILED;
        closeFileChannel();

        // 删除未完成的文件
        if (filePath != null) {
            try {
                java.nio.file.Files.deleteIfExists(Paths.get(filePath));
                log.warn("上传失败，已删除未完成文件: taskId={}, reason={}", taskId, reason);
            } catch (IOException e) {
                log.error("删除未完成文件失败: taskId={}", taskId, e);
            }
        }
    }

    /**
     * 安全释放信号量许可
     * 需要在上传完成或失败后调用，避免信号量泄漏
     * 
     * @param semaphore 信号量实例
     */
    public void releaseSemaphore(java.util.concurrent.Semaphore semaphore) {
        if (semaphoreAcquired && semaphore != null) {
            semaphore.release();
            semaphoreAcquired = false;
            log.debug("释放上传并发许可: taskId={}", taskId);
        }
    }
}
