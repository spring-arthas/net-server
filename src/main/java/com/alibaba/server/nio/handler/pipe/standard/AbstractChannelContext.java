package com.alibaba.server.nio.handler.pipe.standard;

import com.alibaba.server.nio.handler.pipe.ChannelContext;
import com.alibaba.server.nio.handler.pipe.ChannelHandler;
import com.alibaba.server.nio.handler.pipe.ChannelPipeLine;
import com.alibaba.server.nio.service.api.AbstractChannelHandler;
import lombok.extern.slf4j.Slf4j;

/**
 * @Auther: YSFY
 * @Date: 2020-11-21 20:42
 * @Pacage_name: com.alibaba.server.nio.handler.pipe.standard
 * @Project_Name: net-server
 * @Description: 抽象的管道处理器上下文
 */

@Slf4j
@SuppressWarnings("all")
public abstract class AbstractChannelContext implements ChannelContext {

    /**
     * 当前通道上下文持有的ChannelHandler
     */
    private AbstractChannelHandler channelHandler;

    /**
     * 当前通道上下文持有的PipeLine
     */
    private ChannelPipeLine channelPipeLine;

    /**
     * 下一个ChannelContext
     */
    private AbstractChannelContext next;

    /**
     * 当前ChannelContext处于Pipeline中的索引位置
     */
    private Integer index;

    /**
     * 是否需要执行ChannelContext, 默认不需要跳过
     */
    private Boolean needSkip = Boolean.FALSE;

    /**
     * 需要跳过几个ChannelContext
     */
    private int skip = 0;

    /**
     * 是否需要在当前ChannelContext作为执行结尾
     */
    private Boolean needStop = Boolean.FALSE;

    /**
     * 当前通道上下文持有的中转数据
     */
    private Object obj;

    /**
     * 当前通道对应的 SocketChannelContext
     */
    private com.alibaba.server.nio.model.SocketChannelContext socketChannelContext;

    /**
     * 跳过指定个数的ChannelHandler数量
     * 
     * @param isSkip 是否需要跳过ChannelContext
     * @param skip   跳过skip指定数量的channelContext
     * @return channelContext 返回跳跃过后的ChannelContext
     * @return channelContext 返回跳跃过后的ChannelContext
     */
    public abstract ChannelContext skip(Boolean isSkip, ChannelContext startChannelContext, int skip);

    @Override
    public AbstractChannelContext next() {
        return this.getNext();
    }

    public AbstractChannelContext getNext() {
        return next;
    }

    public AbstractChannelContext setNext(AbstractChannelContext next) {
        this.next = next;
        return this;
    }

    public AbstractChannelHandler getChannelHandler() {
        return channelHandler;
    }

    public AbstractChannelContext setChannelHandler(AbstractChannelHandler channelHandler) {
        this.channelHandler = channelHandler;
        return this;
    }

    public ChannelPipeLine getChannelPipeLine() {
        return channelPipeLine;
    }

    public void setChannelPipeLine(ChannelPipeLine channelPipeLine) {
        this.channelPipeLine = channelPipeLine;
    }

    public Integer getIndex() {
        return index;
    }

    public void setIndex(Integer index) {
        this.index = index;
    }

    public Boolean getNeedSkip() {
        return needSkip;
    }

    public AbstractChannelContext setNeedSkip(Boolean needSkip) {
        this.needSkip = needSkip;
        return this;
    }

    public Boolean getNeedStop() {
        return needStop;
    }

    public AbstractChannelContext setNeedStop(Boolean needStop) {
        this.needStop = needStop;
        return this;
    }

    public int getSkip() {
        return skip;
    }

    public AbstractChannelContext setSkip(int skip) {
        this.skip = skip;
        return this;
    }

    public Object getObj() {
        return obj;
    }

    public AbstractChannelContext setObj(Object obj) {
        this.obj = obj;
        return this;
    }

    public com.alibaba.server.nio.model.SocketChannelContext getSocketChannelContext() {
        return socketChannelContext;
    }

    public AbstractChannelContext setSocketChannelContext(
            com.alibaba.server.nio.model.SocketChannelContext socketChannelContext) {
        this.socketChannelContext = socketChannelContext;
        return this;
    }
}
