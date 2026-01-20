package com.alibaba.server.nio.service.file.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 文件上传配置类
 * 通过 application.yml 配置文件上传的速率限制和并发控制参数
 * 
 * 配置示例：
 * file:
 *   upload:
 *     per-connection-rate-bps: 2097152  # 2MB/s
 *     global-rate-bps: 20971520          # 20MB/s
 *     max-concurrent-uploads: 30
 *     enable-dynamic-rate-adjustment: false
 * 
 * @author YSFY
 */
@Data
@Component
@ConfigurationProperties(prefix = "file.upload")
public class FileUploadConfig {

    /**
     * 单连接上传速率限制（字节/秒）
     * 默认：2MB/s
     */
    private long perConnectionRateBps = 2 * 1024 * 1024;

    /**
     * 全局总上传速率限制（字节/秒）
     * 用于控制服务器总带宽，防止上传流量耗尽网络资源
     * 默认：20MB/s
     */
    private long globalRateBps = 20 * 1024 * 1024;

    /**
     * 最大并发上传数
     * 超过此数量的上传请求将被拒绝
     * 默认：30
     */
    private int maxConcurrentUploads = 30;

    /**
     * 是否启用动态速率调整
     * 启用后，单连接速率会根据当前活跃上传数动态调整
     * 默认：false（不启用，保证每个连接的速率稳定性）
     */
    private boolean enableDynamicRateAdjustment = false;

    /**
     * 令牌桶容量倍数
     * 容量 = 速率 * 倍数，用于允许短时突发流量
     * 默认：2
     */
    private int bucketCapacityMultiplier = 2;

    /**
     * 获取单连接令牌桶容量
     */
    public long getPerConnectionBucketCapacity() {
        return perConnectionRateBps * bucketCapacityMultiplier;
    }

    /**
     * 获取全局令牌桶容量
     */
    public long getGlobalBucketCapacity() {
        return globalRateBps * bucketCapacityMultiplier;
    }

    /**
     * 根据当前并发数计算动态速率（如果启用）
     * 
     * @param currentUploads 当前活跃上传数
     * @return 调整后的速率
     */
    public long calculateDynamicRate(int currentUploads) {
        if (!enableDynamicRateAdjustment || currentUploads <= 0) {
            return perConnectionRateBps;
        }
        
        // 确保总速率不超过全局限制，同时保证单连接最低速率 512KB/s
        long dynamicRate = globalRateBps / currentUploads;
        long minRate = 512 * 1024; // 512KB/s
        
        return Math.max(minRate, Math.min(perConnectionRateBps, dynamicRate));
    }
}
