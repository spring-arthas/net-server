package com.alibaba.server.nio.model.resumable;

import com.alibaba.server.nio.model.file.FileUploadContext;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;

@Slf4j
@Data
public class ResumableContext {
    private String taskId;
    private String fileName;
    private String md5;
    private long fileSize;
    private String filePath;
    private RandomAccessFile randomAccessFile;
    private FileChannel fileChannel;
    private long currentOffset;
    private String remoteAddress;

    /**
     * 上传状态
     */
    public enum UploadStatus {
        INITIALIZED, // 已初始化
        UPLOADING, // 上传中
        COMPLETED, // 已完成
        FAILED // 失败
    }

    private ResumableContext.UploadStatus status = ResumableContext.UploadStatus.INITIALIZED;


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
     * 已写入字节数
     */
    private long bytesWritten = 0;

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

        log.debug("写入数据: taskId={}, written={}, total={}/{}",
                taskId, totalWritten, bytesWritten, fileSize);

        return totalWritten;
    }

    /**
     * 标记上传完成
     */
    public void markCompleted() {
        this.status = ResumableContext.UploadStatus.COMPLETED;
        closeFileChannel();
        log.info("文件上传完成: taskId={}, fileName={}, size={}，文件通道关闭成功: bytesWritten={}/{}", taskId, fileName, fileSize,
                bytesWritten, fileSize);
    }
    
    // 用于下载
    private long startOffset;

    public void openUploadChannel() throws IOException {
        File file = new File(filePath);
        if (!file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }
        this.randomAccessFile = new RandomAccessFile(file, "rw");
        this.fileChannel = this.randomAccessFile.getChannel();
        this.currentOffset = this.randomAccessFile.length();
        this.fileChannel.position(this.currentOffset);
        this.status = ResumableContext.UploadStatus.UPLOADING;
    }

    public void openDownloadChannel() throws IOException {
        File file = new File(filePath);
        this.randomAccessFile = new RandomAccessFile(file, "r");
        this.fileChannel = this.randomAccessFile.getChannel();
        if (this.startOffset > 0) {
            this.fileChannel.position(this.startOffset);
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
}
