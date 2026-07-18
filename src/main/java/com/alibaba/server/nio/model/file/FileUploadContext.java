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
     * 传输任务请求ID（唯一标识一次上传）
     */
    private String requestTaskId;
    /**
     * 传输任务数据库记录 ID（用于断连时删除记录）
     */
    private Long fileTaskId;
    /**
     * 真实文件 ID
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

    private Integer userId;

    private String userName;

    /**
     * 上传状态
     */
    public enum UploadStatus {
        INITIALIZED, // 已初始化
        UPLOADING, // 上传中
        FINALIZING, // 数据已接收，正在校验并入库
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
    /**
     * 起始偏移量（断点续传时非0）
     */
    private long startOffset = 0;

    /**
     * 续传时实际使用的磁盘偏移量（由 openFileChannel 内部读取磁盘后确定）
     * 可能与 startOffset(checkpoint值) 不同！客户端应以此值为准
     */
    private long actualResumeOffset = 0;

    /**
     * 上次持久化保存的时间（毫秒）
     */
    private long lastSavedTime = 0;
    /**
     * 上次持久化保存的偏移量
     */
    private long lastSavedOffset = 0;
    /**
     * 最后活跃时间（毫秒，用于超时清理）
     */
    private long lastActiveTime = System.currentTimeMillis();

    /**
     * 是否已记录第一次写入日志（用于诊断续传偏移正确性）
     */
    private boolean firstWriteLogged = false;

    /** 当前 ACK 窗口累计写入字节数。 */
    private long windowBytesWritten = 0L;

    /** 当前 ACK 窗口累计写盘耗时，单位纳秒。 */
    private long windowWriteNanos = 0L;

    /**
     * 更新最后活跃时间
     */
    public void touch() {
        this.lastActiveTime = System.currentTimeMillis();
    }

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
            this.filePath = basePath + "/" + requestTaskId + "_" + fileName;
        } else {
            // 使用默认路径
            LocalDateTime now = LocalDateTime.now();
            String datePath = now.format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
            this.filePath = FILE_STORAGE_ROOT + datePath + "/" + requestTaskId + "_" + fileName;
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
            // *** 断点续传：以磁盘真实大小为唯一权威来源 ***
            // 先读磁盘大小，再决定续传位置，而不是盲目信任 checkpoint/startOffset

            // 第一步：读取磁盘文件真实大小
            long diskFileSize = java.nio.file.Files.size(path);

            // 第二步：确定实际的续传偏移
            // 若磁盘大小 < checkpoint 记录的 startOffset（checkpoint 数据偏大），取磁盘大小
            // 若磁盘大小 > checkpoint 记录的 startOffset（有多余数据尾巴），也取磁盘大小
            // 这两种情况下磁盘大小才是真正安全的续传起点
            // 但是，如果磁盘大小 > 期望的 startOffset，说明有脏数据尾巴，需要截断
            // 若磁盘大小 < startOffset，代表有数据丢失，只能从磁盘有效字节末尾续传
            this.actualResumeOffset = diskFileSize; // 默认用磁盘大小作为续传位置

            // 第三步：打开文件通道（WRITE + CREATE 模式，不使用 APPEND）
            // *** BUG FIX: 加上 CREATE 选项 ***
            // 防止 Files.exists() 检查通过后、open() 调用前文件被意外删除
            // 若文件已存在，CREATE 不会截断，仅在不存在时新建空文件，安全。
            this.fileChannel = FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.CREATE);

            // 第四步：如果磁盘大小 > startOffset（有多余的脏数据尾巴），截断
            if (diskFileSize > startOffset) {
                this.fileChannel.truncate(startOffset);
                this.actualResumeOffset = startOffset; // 截断后以 startOffset 为准
                log.warn("断点续传截断: 磁盘大小({}) > checkpoint({})，截断到 checkpoint，续传偏移={}",
                        diskFileSize, startOffset, this.actualResumeOffset);
            } else if (diskFileSize < startOffset) {
                // 磁盘数据比 checkpoint 少（数据丢失），只能从磁盘末尾续传
                this.actualResumeOffset = diskFileSize;
                log.warn("断点续传数据丢失: 磁盘大小({}) < checkpoint({})，从磁盘末尾续传，续传偏移={}",
                        diskFileSize, startOffset, this.actualResumeOffset);
            } else {
                log.info("断点续传正常: 磁盘大小({}) == checkpoint({})，续传偏移={}",
                        diskFileSize, startOffset, this.actualResumeOffset);
            }

            // 第五步：将文件通道位置精确定位到 actualResumeOffset
            this.fileChannel.position(this.actualResumeOffset);

            // 初始化已写入字节数为实际续传偏移量
            this.bytesWritten = this.actualResumeOffset;

            log.info("断点续传通道就绪 - taskId: {}, 文件: {}, 续传偏移={} bytes, channel.position()={} bytes",
                    requestTaskId, fileName, this.actualResumeOffset, this.fileChannel.position());
        } else {
            // 全新上传：覆盖模式（TRUNCATE_EXISTING）
            this.fileChannel = FileChannel.open(path,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);

            this.bytesWritten = 0;
            log.info("全新上传模式 - taskId: {}, 文件: {}", requestTaskId, fileName);
        }

        this.status = UploadStatus.UPLOADING;
        this.startTime = LocalDateTime.now();

        log.info("打开文件通道: taskId={}, filePath={}", requestTaskId, filePath);
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

        // *** 关键诊断：仅在第一次写入时记录 channel 精确位置 ***
        // 对于续传场景，此值应精确等于 actualResumeOffset
        // 若不等，说明 seek 有问题或 channel 被意外修改
        if (!firstWriteLogged) {
            firstWriteLogged = true;
            try {
                long channelPos = fileChannel.position();
                log.warn(
                        "[诊断] 首次写入 - taskId={}, isResume={}, actualResumeOffset={}, bytesWritten={}, channel.position()={}, 三者{}一致",
                        requestTaskId, isResume, actualResumeOffset, bytesWritten, channelPos,
                        (channelPos == actualResumeOffset && channelPos == bytesWritten) ? "完全" : "【不】");
            } catch (IOException e) {
                log.error("[诊断] 读取 channel position 失败", e);
            }
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
     * 记录当前 ACK 窗口的一次写盘指标。
     *
     * @param writtenBytes 实际写入字节数
     * @param writeNanos 写盘耗时，单位纳秒
     */
    public synchronized void recordWindowWrite(long writtenBytes, long writeNanos) {
        if (writtenBytes > 0L) {
            windowBytesWritten += writtenBytes;
        }
        if (writeNanos > 0L) {
            windowWriteNanos += writeNanos;
        }
    }

    public synchronized long getWindowBytesWritten() {
        return windowBytesWritten;
    }

    public synchronized long getWindowWriteMillis() {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(windowWriteNanos);
    }

    /** 重置当前 ACK 窗口指标。 */
    public synchronized void resetWindowMetrics() {
        windowBytesWritten = 0L;
        windowWriteNanos = 0L;
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
                log.error("关闭文件通道失败: taskId={}", requestTaskId, e);
            }
        }
    }

    /**
     * 标记上传完成
     */
    public void markCompleted() {
        this.status = UploadStatus.COMPLETED;
        closeFileChannel();
        log.info("文件上传完成: taskId={}, fileName={}, size={}，文件通道关闭成功: bytesWritten={}/{}", requestTaskId, fileName,
                fileSize,
                bytesWritten, fileSize);
    }

    /** 标记为完成校验中，断连清理不得再将任务回写为暂停。 */
    public void markFinalizing() {
        this.status = UploadStatus.FINALIZING;
        closeFileChannel();
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
                log.warn("上传失败，已删除未完成文件: taskId={}, reason={}", requestTaskId, reason);
            } catch (IOException e) {
                log.error("删除未完成文件失败: taskId={}", requestTaskId, e);
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
            log.debug("释放上传并发许可: taskId={}", requestTaskId);
        }
    }
}
