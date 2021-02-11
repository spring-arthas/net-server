package com.alibaba.server.nio.service.api;

import com.alibaba.server.nio.handler.pipe.ChannelContext;
import java.io.IOException;

/**
 * 通道处理器
 *
 * @author spring
 * */

public interface ChannelHandler {

    /**
     * 通道任务处理
     *
     * @param t
     * @param channelContext
     *
     * @return
     * */
    void handler(Object t, ChannelContext channelContext) throws IOException;
}
