package com.alibaba.server.nio.model.file;

import lombok.Data;

/**
 * Pull-Range 会话上下文。
 */
@Data
public class RangePullSessionContext {

    private String taskId;
    private String remoteAddress;
    private Long fileId;
    private long fileSize;

    private String lastRequestId;
    private long currentWindowStart;
    private long currentWindowLength;
    private long windowSentBytes;

    private long lastActiveTime = System.currentTimeMillis();

    public void touch() {
        this.lastActiveTime = System.currentTimeMillis();
    }
}
