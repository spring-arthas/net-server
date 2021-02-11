package com.alibaba.server.nio.handler.pipe;

import java.io.IOException;

/**
 * @Auther: YSFY
 * @Date: 2020-11-21 20:33
 * @Pacage_name: com.alibaba.server.nio.handler.pipe
 * @Project_Name: net-server
 * @Description: 通道处理器
 */

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
