package com.alibaba.server.nio.handler.pipe;

import com.alibaba.server.nio.handler.pipe.standard.AbstractChannelContext;
import com.alibaba.server.nio.service.api.AbstractChannelHandler;

/**
 * @Auther: YSFY
 * @Date: 2020-11-21 20:32
 * @Pacage_name: com.alibaba.server.nio.handler.pipe
 * @Project_Name: net-server
 * @Description: 处理管道ChannelPipeLine
 */

public interface ChannelPipeLine {

    /**
     * 添加ChannelHandler
     *
     * @param channelContext
     *
     * @param abstractChannelHandler
     *
     * @return channelHandler
     * */
    ChannelPipeLine addHandler(AbstractChannelContext channelContext, AbstractChannelHandler abstractChannelHandler);
}
