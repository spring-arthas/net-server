package com.alibaba.server.nio.model.file;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.time.LocalDateTime;

/**
 * 文件下载上下文
 * 管理下载任务的状态、文件通道和进度
 *
 * @author YSFY
 */
@Data
@Slf4j
public class FileDownloadContext {

    /**
     * 任务ID（客户端请求ID）
     */
    private String taskId;

    /**
     * 文件ID（数据库记录ID）
     */
    private Long fileId;

    /**
     * 传输任务ID（数据库记录ID）
     */
    private Long fileTaskId;

    /**
     * 文件名
     */
    private String fileName;

    /**
     * 文件对象
     */
    private File file;

    /**
     * 文件总大小
     */
    private long fileSize;

    /**
     * 起始偏移量（断点续传位置）
     */
    private long startOffset;

    /**
     * 当前读取位置
     */
    private long currentOffset;

    /**
     * 客户端远程地址
     */
    private String remoteAddress;

    /**
     * 开始时间
     */
    private LocalDateTime startTime;

    /**
     * 最后活跃时间
     */
    /**
     * 最后活跃时间
     */
    private long lastActiveTime;

    /**
     * 上次持久化保存的时间（毫秒）
     */
    private long lastSavedTime = 0;
    /**
     * 上次持久化保存的偏移量
     */
    private long lastSavedOffset = 0;

    /**
     * 随机访问文件（支持 seek）
     */
    private RandomAccessFile randomAccessFile;

    /**
     * 文件通道
     */
    private FileChannel fileChannel;

    /**
     * 下载状态
     */
    private DownloadStatus status;

    public enum DownloadStatus {
        INIT, // 初始化
        READY, // 以此状态等待ACK
        DOWNLOADING, // 传输中
        COMPLETED, // 完成
        FAILED // 失败
    }

    public FileDownloadContext() {
        this.startTime = LocalDateTime.now();
        this.lastActiveTime = System.currentTimeMillis();
        this.status = DownloadStatus.INIT;
    }

    /**
     * 初始化文件通道
     */
    public void openFileChannel() throws FileNotFoundException {
        if (file == null || !file.exists()) {
            throw new FileNotFoundException("文件不存在: " + (file != null ? file.getPath() : "null"));
        }
        this.randomAccessFile = new RandomAccessFile(file, "r");
        this.fileChannel = this.randomAccessFile.getChannel();
    }

    /**
     * 读取下一块数据
     * 
     * @param buffer 接收数据的缓冲区
     * @return 读取的字节数，-1 表示文件结束
     */
    public int readChunk(ByteBuffer buffer) throws IOException {
        if (fileChannel == null || !fileChannel.isOpen()) {
            throw new IOException("文件通道未打开");
        }

        // 确保从正确的位置读取
        if (fileChannel.position() != currentOffset) {
            fileChannel.position(currentOffset);
        }

        int bytesRead = fileChannel.read(buffer);
        if (bytesRead > 0) {
            currentOffset += bytesRead;
            lastActiveTime = System.currentTimeMillis();
        }
        return bytesRead;
    }

    /**
     * 关闭资源
     */
    public void close() {
        try {
            if (fileChannel != null) {
                fileChannel.close();
            }
            if (randomAccessFile != null) {
                randomAccessFile.close();
            }
            log.debug("关闭文件下载通道: taskId={}", taskId);
        } catch (IOException e) {
            log.error("关闭文件下载通道失败: taskId={}", taskId, e);
        }
    }

    /**
     * 更新最后活跃时间
     */
    public void touch() {
        this.lastActiveTime = System.currentTimeMillis();
    }
}
