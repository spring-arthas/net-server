package com.alibaba.server.nio.service.file.adaptive;

/**
 * 自适应调压策略表
 * 根据当前资源压力等级，输出各处理器允许的最大并发数和速率系数。
 * 调压只影响新建连接，不中断进行中的传输。
 *
 * 并发上限策略（拉流随机读最损耗磁盘，降幅最大）：
 * <pre>
 * 压力等级   NORMAL  MODERATE  HIGH  CRITICAL
 * 上传并发     30       20      10       3
 * 下载并发      5        4       2       1
 * 拉流并发      5        3       2       1
 * 上传速率    100%      70%     40%     20%
 * </pre>
 */
public class AdaptiveThrottlePolicy {

    // 各等级对应的并发上限，下标对应 ResourcePressureLevel.getLevel()
    private static final int[]    UPLOAD_MAX_CONCURRENT    = {30, 20, 10, 3};
    private static final int[]    DOWNLOAD_MAX_CONCURRENT  = { 5,  4,  2, 1};
    private static final int[]    RANGEPULL_MAX_CONCURRENT = { 5,  3,  2, 1};
    // 各等级对应的上传速率系数（叠加在原有速率之上）
    private static final double[] UPLOAD_RATE_MULTIPLIER   = {1.0, 0.7, 0.4, 0.2};

    private AdaptiveThrottlePolicy() {}

    public static int uploadMaxConcurrent() {
        return UPLOAD_MAX_CONCURRENT[level()];
    }

    public static int downloadMaxConcurrent() {
        return DOWNLOAD_MAX_CONCURRENT[level()];
    }

    public static int rangePullMaxConcurrent() {
        return RANGEPULL_MAX_CONCURRENT[level()];
    }

    public static double uploadRateMultiplier() {
        return UPLOAD_RATE_MULTIPLIER[level()];
    }

    private static int level() {
        return ServerResourceMonitor.getInstance().getCurrentLevel().getLevel();
    }
}
