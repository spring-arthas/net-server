package com.alibaba.server.nio.service.file.adaptive;

import lombok.Getter;

/**
 * 上传背压决策。
 */
@Getter
public final class UploadBackpressureDecision {

    private final String serverState;
    private final int recommendedChunkSize;
    private final int recommendedAckWindow;
    private final long serverWriteMillis;
    private final long retryAfterMs;

    public UploadBackpressureDecision(String serverState,
            int recommendedChunkSize,
            int recommendedAckWindow,
            long serverWriteMillis,
            long retryAfterMs) {
        this.serverState = serverState;
        this.recommendedChunkSize = recommendedChunkSize;
        this.recommendedAckWindow = recommendedAckWindow;
        this.serverWriteMillis = serverWriteMillis;
        this.retryAfterMs = retryAfterMs;
    }
}
