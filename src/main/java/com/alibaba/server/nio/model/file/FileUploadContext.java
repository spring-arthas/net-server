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
 * @author YSFY
 */
@Data
@Slf4j
public class FileUploadContext {

    /**
     * 文件存储根目录
     */
    private static final String FILE_STORAGE_ROOT = "/data/file-storage/";

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
     * 已写入字节数
     */
    private long bytesWritten = 0;

    /**
     * 文件通道
     */
    private FileChannel fileChannel;

    /**
     * 文件存储路径
     */
    private String filePath;

    /**
     * 上传开始时间
     */
    private LocalDateTime startTime;

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
     * 生成唯一任务ID
     */
    public static String generateTaskId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 构建文件存储路径
     * 格式：/data/file-storage/yyyy/MM/dd/{taskId}_{fileName}
     */
    public String buildFilePath() {
        LocalDateTime now = LocalDateTime.now();
        String datePath = now.format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        this.filePath = FILE_STORAGE_ROOT + datePath + "/" + taskId + "_" + fileName;
        return this.filePath;
    }

    /**
     * 打开文件通道
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

        this.fileChannel = FileChannel.open(path,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);

        this.status = UploadStatus.UPLOADING;
        this.startTime = LocalDateTime.now();

        log.info("打开文件通道: taskId={}, filePath={}", taskId, filePath);
        return this.fileChannel;
    }

    /**
     * 写入数据
     */
    public int writeData(byte[] data) throws IOException {
        if (fileChannel == null || !fileChannel.isOpen()) {
            throw new IOException("文件通道未打开或已关闭");
        }

        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(data);
        int written = fileChannel.write(buffer);
        bytesWritten += written;

        log.debug("写入数据: taskId={}, written={}, total={}/{}",
                taskId, written, bytesWritten, fileSize);

        return written;
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
                log.info("关闭文件通道: taskId={}, bytesWritten={}/{}",
                        taskId, bytesWritten, fileSize);
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
        log.info("文件上传完成: taskId={}, fileName={}, size={}", taskId, fileName, fileSize);
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
}
