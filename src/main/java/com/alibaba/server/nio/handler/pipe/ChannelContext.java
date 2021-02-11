package com.alibaba.server.nio.handler.pipe;

import com.alibaba.server.nio.handler.pipe.standard.AbstractChannelContext;

/**
 * @Auther: YSFY
 * @Date: 2020-11-21 20:34
 * @Pacage_name: com.alibaba.server.nio.handler.pipe
 * @Project_Name: net-server
 * @Description: 管道与处理器上下文
 */

public interface ChannelContext {

    /**
     * 返回下一个ChannelHandler
     *
     * @param
     *
     * @return channelHandler
     * */
    AbstractChannelContext next();
}
