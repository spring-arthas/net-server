package com.alibaba.server.nio.service.ratelimit;

import lombok.extern.slf4j.Slf4j;

/**
 * 基于令牌桶算法的速率限制器
 * 线程不安全（设计用于单线程 ReadEventHandler 场景）
 */
@Slf4j
public class TokenBucketRateLimiter implements RateLimiter {

    private final long capacity;      // 桶容量（最大突发量）基本容量 -> 最大容量
    private final long ratePerSec;    // 速率（字节/秒）即该桶每隔多久放入多少个令牌，放多少个令牌即表示读事件可以从内核缓冲区读取多少字节的数据
                                      // 读取多少字节的数据，桶里没有令牌则不允许读事件从内核缓冲区读取数据
    private double tokens;            // 当前令牌数，即当前桶内令牌数，即读事件可以从内核缓冲区读取多少字节数据
    private long lastRefillTime;      // 上次补充时间 单位：纳秒，当前时间 - 上次补充token的时间 = 经过了多少时间，经过的时间 * ratePerSec = 表示过了这么长时间可以补充多少token

    public TokenBucketRateLimiter(long ratePerSec, long capacity) {
        this.ratePerSec = ratePerSec;
        this.capacity = capacity;
        this.tokens = capacity; // 初始满桶
        this.lastRefillTime = System.nanoTime();
    }

    @Override
    public boolean tryConsume(long requestTokens) {
        // 尝试填充token
        refill();
        if (tokens >= requestTokens) {
            tokens -= requestTokens;
            return true;
        }
        return false;
    }
    @Override
    public void refill() {
        // 当前时间
        long now = System.nanoTime();
        // 经过了多长时间，当前时间 - 上次填充token到桶的时间
        long duration = now - lastRefillTime;
        if (duration > 0) {
            // 只有当前时间大于0才需要进行补充
            // duration (ns) * rate (bytes/s) / 1e9 = new tokens
            double needNewTokens = (duration * ratePerSec) / 1_000_000_000.0;
            if (needNewTokens > 0) {
                // 本次经过的时间需要补充的令牌数needNewTokens大于0，那么就补充令牌，此时需要
                // 保证当前桶内现有令牌数+需要补充的令牌数不能超过桶的最大容量，所以二者取最小值
                tokens = Math.min(capacity, tokens + needNewTokens);
                lastRefillTime = now;
            }
        }
    }

    @Override
    public long getAvailableTokens() {
        refill();
        return (long) tokens;
    }

    /**
     * 计算等待所需令牌的时间（毫秒）
     *  代码注释
     *  - if (ratePerSec == 0) return Long.MAX_VALUE;
        - ratePerSec 表示“每秒生成多少令牌（字节/秒）”
        - 如果速率是 0，意味着永远不会补充令牌（完全禁止/异常配置），那就返回一个极大的等待时间，等同于“无限等待”。
        - long missingTokens = requiredTokens - (long) tokens;

        - requiredTokens ：你希望至少攒够的令牌数（字节数），例如在 ReadEventHandler 里用的是 1024 ，意味着“至少等到能读 1KB 再恢复读”。
        - tokens ：当前桶里已有的令牌（用 double 存储，这里强转 long 会截断小数部分）。
        - missingTokens ：还差多少令牌。
        - if (missingTokens <= 0) return 0;

        - 如果桶里令牌已经够了，就不需要等待，立即可读。
        - return (long) ((missingTokens * 1000.0) / ratePerSec);

        - 这是单位换算：
        - missingTokens / ratePerSec 得到 需要等待的秒数 （因为 ratePerSec 是 “tokens per second”）
        - 乘以 1000.0 转成 毫秒
        - 最后 cast 成 long ，得到整数毫秒（会向下取整）。
     * @param requiredTokens 需要的令牌数
     * @return 等待时间（ms）
     * 
     */
    @Override
    public long calculateWaitTime(long requiredTokens) {
        if (ratePerSec == 0) return Long.MAX_VALUE;
        long missingTokens = requiredTokens - (long) tokens;
        if (missingTokens <= 0) return 0;
        // 缺少 tokens / rate = 需要的秒数 -> * 1000 = 毫秒
        return (long) ((missingTokens * 1000.0) / ratePerSec);
    }
}
