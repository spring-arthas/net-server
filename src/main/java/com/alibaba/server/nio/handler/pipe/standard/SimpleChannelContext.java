package com.alibaba.server.nio.handler.pipe.standard;

import com.alibaba.server.nio.handler.pipe.ChannelContext;
import com.alibaba.server.nio.handler.pipe.ChannelPipeLine;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

/**
 * @Auther: YSFY
 * @Date: 2020-11-21 20:50
 * @Pacage_name: com.alibaba.server.nio.handler.pipe.standard
 * @Project_Name: net-server
 * @Description: 简单ChannelContext
 */

@Slf4j
@SuppressWarnings("all")
public class SimpleChannelContext extends AbstractChannelContext {

    public SimpleChannelContext() {

    }

    public SimpleChannelContext(ChannelPipeLine channelPipeLine) {
        super.setChannelPipeLine(channelPipeLine);
    }

    /**
     * 当前持有的Handler是否需要被执行
     * */
    private Boolean isNeedHandler = Boolean.TRUE;

    public Boolean getNeedHandler() {
        return isNeedHandler;
    }

    public void setNeedHandler(Boolean needHandler) {
        isNeedHandler = needHandler;
    }

    /**
     * 跳过指定个数的ChannelHandler数量，只能往后跳跃，不能向前跳跃
     * @param isSkip 是否需要跳过ChannelContext
     * @param startChannelContext 从哪个ChannelContext开始跳跃
     * @param skip 跳过skip指定数量的channelContext
     * @return channelContext 返回跳跃过后的ChannelContext
     */
    @Override
    public ChannelContext skip(Boolean isSkip, ChannelContext startChannelContext, int skip) {
        // 1、判断是否需要执行channelContext跳过
        if(Boolean.FALSE.equals(isSkip)) {
            return startChannelContext;
        }

        // 2、判断跳跃数量是否已超过当前ChannelContext所属的PipeLine持有的ChannelContext最大值
        SimpleChannelContext simpleChannelContext = (SimpleChannelContext) startChannelContext;
        int index = simpleChannelContext.getIndex();
        if((index + skip) > ((DefaultChannelPipeLine) simpleChannelContext.getChannelPipeLine()).getHandlerCount()) {
            throw new RuntimeException("ChannelPipeline IndexOutOfBounds exception");
        }

        // 3、判断当前ChannelContext是否为空，如果为空直接返回null
        ChannelContext channelContext = null;
        if(!Optional.ofNullable(startChannelContext).isPresent()) {
            return channelContext;
        }

        // 4、判断跳跃数量是否大于0
        if(skip < 1) {
            return channelContext;
        }

        // 5、获取当前起始ChannelContext的下一个ChannelContext
        ChannelContext nextChannelContext = startChannelContext.next();
        while (null != nextChannelContext && skip > 0) {
            channelContext = nextChannelContext.next();
            nextChannelContext = channelContext;
            skip = skip - 1;
        }

        // 6、设置传递的参数值
        if(channelContext != null) {
            ((SimpleChannelContext) channelContext).setObj(((SimpleChannelContext) startChannelContext).getObj());
        }
        return channelContext;
    }
}
