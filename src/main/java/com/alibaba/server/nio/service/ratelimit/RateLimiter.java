package com.alibaba.server.nio.service.ratelimit;

/**
 * 速率限制器接口
 */
public interface RateLimiter {

    /**
     * 尝试消耗令牌
     *
     * @param tokens 需要消耗的令牌数（字节数）
     * @return 如果足够则返回 true，否则返回 false
     */
    boolean tryConsume(long tokens);

    /**
     * 获取当前可用令牌数
     */
    long getAvailableTokens();

    /**
     * 计算等待所需令牌的时间（毫秒）
     *
     * @param requiredTokens 需要的令牌数
     * @return 等待时间（ms）
     */
    long calculateWaitTime(long requiredTokens);

    /**
     * 补充令牌（通常由定时器或每次调用时触发）
     */
    void refill();
}
