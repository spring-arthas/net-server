package com.alibaba.server.nio.service.file.adaptive;

/**
 * 服务端资源压力等级
 * 由 ServerResourceMonitor 根据 CPU、堆内存、磁盘IO 综合计算后输出
 */
public enum ResourcePressureLevel {

    /** 正常：CPU<60%, 堆<70%, 磁盘IO<60% */
    NORMAL(0),
    /** 适中：任一指标 60-75%，开始收紧并发 */
    MODERATE(1),
    /** 高压：任一指标 75-90%，显著限速 */
    HIGH(2),
    /** 危机：任一指标 >90%，只保底服务 */
    CRITICAL(3);

    private final int level;

    ResourcePressureLevel(int level) {
        this.level = level;
    }

    public int getLevel() {
        return level;
    }
}
