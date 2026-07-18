package com.alibaba.server.nio.service.ratelimit;

import lombok.extern.slf4j.Slf4j;

/**
 * 基于令牌桶算法的速率限制器
 * 线程安全版本：所有公共方法都使用 synchronized 保护
 * 
 * @author YSFY
 */
@Slf4j
public class TokenBucketRateLimiter implements RateLimiter {

    private final long capacity;      // 桶容量（最大突发量）
    private final long ratePerSec;    // 速率（字节/秒）
    private double tokens;            // 当前令牌数（synchronized 保护）
    private long lastRefillTime;      // 上次补充时间（纳秒）

    public TokenBucketRateLimiter(long ratePerSec, long capacity) {
        this.ratePerSec = ratePerSec;
        this.capacity = capacity;
        this.tokens = capacity; // 初始满桶
        this.lastRefillTime = System.nanoTime();
    }

    @Override
    public synchronized boolean tryConsume(long requestTokens) {
        // 尝试填充token
        doRefill();
        if (tokens >= requestTokens) {
            tokens -= requestTokens;
            return true;
        }
        return false;
    }

    @Override
    public synchronized void refill() {
        doRefill();
    }

    /**
     * 内部补充方法（调用者需持有锁）
     */
    private void doRefill() {
        long now = System.nanoTime();
        long duration = now - lastRefillTime;
        if (duration > 0) {
            double needNewTokens = (duration * ratePerSec) / 1_000_000_000.0;
            if (needNewTokens > 0) {
                tokens = Math.min(capacity, tokens + needNewTokens);
                lastRefillTime = now;
            }
        }
    }

    @Override
    public synchronized long getAvailableTokens() {
        doRefill();
        return (long) tokens;
    }

    /**
     * 计算等待所需令牌的时间（毫秒）
     * 
     * @param requiredTokens 需要的令牌数
     * @return 等待时间（ms）
     */
    @Override
    public synchronized long calculateWaitTime(long requiredTokens) {
        if (ratePerSec == 0) return Long.MAX_VALUE;
        doRefill(); // 先补充，获取最新令牌数
        long missingTokens = requiredTokens - (long) tokens;
        if (missingTokens <= 0) return 0;
        return (long) ((missingTokens * 1000.0) / ratePerSec);
    }
}
