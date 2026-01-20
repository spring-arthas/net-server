package com.alibaba.server.nio.service.file.config;

import com.alibaba.server.util.PropertiesUtil;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 文件上传配置类
 * 从 server.properties 配置文件读取文件上传的速率限制和并发控制参数
 * 
 * 配置示例（在 server.properties 中添加）：
 * FILE.UPLOAD.PER.CONNECTION.RATE.BPS=2097152
 * FILE.UPLOAD.GLOBAL.RATE.BPS=20971520
 * FILE.UPLOAD.MAX.CONCURRENT.UPLOADS=30
 * FILE.UPLOAD.ENABLE.DYNAMIC.RATE.ADJUSTMENT=false
 * FILE.UPLOAD.BUCKET.CAPACITY.MULTIPLIER=2
 * 
 * @author YSFY
 */
@Slf4j
@Data
@Component
public class FileUploadConfig {

    /**
     * 单连接上传速率限制（字节/秒）
     * 配置键：FILE.UPLOAD.PER.CONNECTION.RATE.BPS
     * 默认：2MB/s
     */
    private long perConnectionRateBps;

    /**
     * 全局总上传速率限制（字节/秒）
     * 用于控制服务器总带宽，防止上传流量耗尽网络资源
     * 配置键：FILE.UPLOAD.GLOBAL.RATE.BPS
     * 默认：20MB/s
     */
    private long globalRateBps;

    /**
     * 最大并发上传数
     * 超过此数量的上传请求将被拒绝
     * 配置键：FILE.UPLOAD.MAX.CONCURRENT.UPLOADS
     * 默认：30
     */
    private int maxConcurrentUploads;

    /**
     * 是否启用动态速率调整
     * 启用后，单连接速率会根据当前活跃上传数动态调整
     * 配置键：FILE.UPLOAD.ENABLE.DYNAMIC.RATE.ADJUSTMENT
     * 默认：false（不启用，保证每个连接的速率稳定性）
     */
    private boolean enableDynamicRateAdjustment;

    /**
     * 令牌桶容量倍数
     * 容量 = 速率 * 倍数，用于允许短时突发流量
     * 配置键：FILE.UPLOAD.BUCKET.CAPACITY.MULTIPLIER
     * 默认：2
     */
    private int bucketCapacityMultiplier;

    /**
     * 构造函数：从 server.properties 加载配置
     */
    public FileUploadConfig() {
        loadConfig();
    }

    /**
     * 从 PropertiesUtil 加载配置
     */
    private void loadConfig() {
        // 单连接速率限制，默认 2MB/s
        this.perConnectionRateBps = getLongProperty("FILE.UPLOAD.PER.CONNECTION.RATE.BPS", 2 * 1024 * 1024);
        
        // 全局速率限制，默认 20MB/s
        this.globalRateBps = getLongProperty("FILE.UPLOAD.GLOBAL.RATE.BPS", 20 * 1024 * 1024);
        
        // 最大并发上传数，默认 30
        this.maxConcurrentUploads = getIntProperty("FILE.UPLOAD.MAX.CONCURRENT.UPLOADS", 30);
        
        // 是否启用动态速率调整，默认 false
        this.enableDynamicRateAdjustment = getBooleanProperty("FILE.UPLOAD.ENABLE.DYNAMIC.RATE.ADJUSTMENT", false);
        
        // 令牌桶容量倍数，默认 2
        this.bucketCapacityMultiplier = getIntProperty("FILE.UPLOAD.BUCKET.CAPACITY.MULTIPLIER", 2);

        log.info("文件上传配置加载完成 - 单连接速率: {} B/s ({} MB/s), 全局速率: {} B/s ({} MB/s), 最大并发: {}, 动态调整: {}, 桶容量倍数: {}",
                perConnectionRateBps, perConnectionRateBps / 1024 / 1024,
                globalRateBps, globalRateBps / 1024 / 1024,
                maxConcurrentUploads,
                enableDynamicRateAdjustment,
                bucketCapacityMultiplier);
    }

    /**
     * 从配置中读取 long 类型值
     */
    private long getLongProperty(String key, long defaultValue) {
        try {
            String value = PropertiesUtil.getValue(key);
            if (value != null && !value.trim().isEmpty()) {
                return Long.parseLong(value.trim());
            }
        } catch (NumberFormatException e) {
            log.warn("解析配置项 {} 失败，使用默认值: {}", key, defaultValue, e);
        }
        return defaultValue;
    }

    /**
     * 从配置中读取 int 类型值
     */
    private int getIntProperty(String key, int defaultValue) {
        try {
            String value = PropertiesUtil.getValue(key);
            if (value != null && !value.trim().isEmpty()) {
                return Integer.parseInt(value.trim());
            }
        } catch (NumberFormatException e) {
            log.warn("解析配置项 {} 失败，使用默认值: {}", key, defaultValue, e);
        }
        return defaultValue;
    }

    /**
     * 从配置中读取 boolean 类型值
     */
    private boolean getBooleanProperty(String key, boolean defaultValue) {
        try {
            String value = PropertiesUtil.getValue(key);
            if (value != null && !value.trim().isEmpty()) {
                return Boolean.parseBoolean(value.trim());
            }
        } catch (Exception e) {
            log.warn("解析配置项 {} 失败，使用默认值: {}", key, defaultValue, e);
        }
        return defaultValue;
    }

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
