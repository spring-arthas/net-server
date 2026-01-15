package com.alibaba.server.nio.model.resumable;

import lombok.Data;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;

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
    }

    public void openDownloadChannel() throws IOException {
        File file = new File(filePath);
        this.randomAccessFile = new RandomAccessFile(file, "r");
        this.fileChannel = this.randomAccessFile.getChannel();
        if (this.startOffset > 0) {
            this.fileChannel.position(this.startOffset);
        }
    }

    public void close() {
        try {
            if (fileChannel != null) fileChannel.close();
            if (randomAccessFile != null) randomAccessFile.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
