package com.alibaba.server.nio.service.file.adaptive;

import com.alibaba.server.nio.service.file.config.FileUploadConfig;

/**
 * 根据服务端资源和上传链路指标生成客户端上传建议。
 */
public final class UploadBackpressureController {

    private static final double SLOW_DOWN_QUEUE_RATIO = 0.60D;
    private static final double PAUSE_QUEUE_RATIO = 0.85D;
    private static final double LOW_GLOBAL_BUDGET_RATIO = 0.15D;
    private static final long SLOW_WRITE_MILLIS = 200L;
    private static final long DEFAULT_PAUSE_MILLIS = 500L;

    private final FileUploadConfig config;

    public UploadBackpressureController(FileUploadConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("文件上传配置不能为空");
        }
        this.config = config;
    }

    /**
     * 生成本次 progress ACK 的背压决策。
     *
     * @param pressureLevel 服务端综合资源压力
     * @param serverWriteMillis ACK窗口累计写盘耗时
     * @param workerQueueRatio 当前通道Worker队列占用率
     * @param activeUploads 当前活跃上传数
     * @param globalBudgetRatio 全局令牌预算剩余比例
     * @return 背压决策
     */
    public UploadBackpressureDecision decide(ResourcePressureLevel pressureLevel,
            long serverWriteMillis,
            double workerQueueRatio,
            int activeUploads,
            double globalBudgetRatio) {
        boolean validMetrics = pressureLevel != null
                && serverWriteMillis >= 0L
                && isRatio(workerQueueRatio)
                && activeUploads > 0
                && isRatio(globalBudgetRatio);

        String state;
        if (pressureLevel == ResourcePressureLevel.CRITICAL
                || (validMetrics && workerQueueRatio >= PAUSE_QUEUE_RATIO)) {
            state = "pause";
        } else if (!validMetrics
                || pressureLevel == ResourcePressureLevel.MODERATE
                || pressureLevel == ResourcePressureLevel.HIGH
                || workerQueueRatio >= SLOW_DOWN_QUEUE_RATIO
                || globalBudgetRatio <= LOW_GLOBAL_BUDGET_RATIO
                || serverWriteMillis >= SLOW_WRITE_MILLIS) {
            state = "slow_down";
        } else {
            state = "normal";
        }

        int chunkSize;
        int ackWindow;
        long retryAfterMs = 0L;
        if ("pause".equals(state)) {
            chunkSize = config.getAdaptiveChunkMinBytes();
            ackWindow = config.getAdaptiveAckInitialBytes();
            retryAfterMs = DEFAULT_PAUSE_MILLIS;
        } else if ("slow_down".equals(state)) {
            chunkSize = config.getAdaptiveChunkInitialBytes();
            ackWindow = config.getAdaptiveAckInitialBytes();
        } else {
            chunkSize = config.getAdaptiveChunkMaxBytes();
            ackWindow = config.getAdaptiveAckMaxBytes();
        }

        return new UploadBackpressureDecision(
                state,
                chunkSize,
                ackWindow,
                Math.max(0L, serverWriteMillis),
                retryAfterMs);
    }

    private boolean isRatio(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value) && value >= 0D && value <= 1D;
    }
}
