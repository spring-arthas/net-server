package com.alibaba.server.nio.handler.pipe.standard;

import com.alibaba.server.nio.handler.pipe.ChannelContext;
import com.alibaba.server.nio.handler.pipe.ChannelPipeLine;
import com.alibaba.server.nio.model.SocketChannelContext;
import com.alibaba.server.nio.service.api.AbstractChannelHandler;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Optional;

/**
 * @Auther: YSFY
 * @Date: 2020-11-21 20:37
 * @Pacage_name: com.alibaba.server.nio.handler.pipe.standard
 * @Project_Name: net-server
 * @Description: 默认的管道实现类
 */

@Slf4j
@SuppressWarnings("all")
public class DefaultChannelPipeLine implements ChannelPipeLine {

    // 头上下文
    private HeadContext headContext = new HeadContext();

    // 当前通道PipeLine持有的ChannelContext数量
    private int handlerCount;

    // ChannelContext 索引
    private int index = 0;

    public DefaultChannelPipeLine() {
        this.headContext.setChannelHandler(null);
        this.headContext.setNext(null);
    }

    /**
     * 向链表ChannelContext追加新节点
     *
     * @param newChannelContext
     * @param channelHandler
     * @return
     */
    @Override
    public ChannelPipeLine addHandler(AbstractChannelContext newChannelContext, AbstractChannelHandler channelHandler) {
        // 获取头context节点
        AbstractChannelContext currentChannelContext = this.headContext;
        // 获取头节点的下一个context节点
        AbstractChannelContext nextChannelContext = this.headContext.next();
        // 链表形态判断找到最后一个为null的context节点
        while (Optional.ofNullable(nextChannelContext).isPresent()) {
            currentChannelContext = nextChannelContext;
            nextChannelContext = nextChannelContext.next();
        }

        // 将当前找到链表尾部为null的context节点变为当前待新增的channelcontext节点
        nextChannelContext = newChannelContext;
        nextChannelContext.setChannelHandler(channelHandler);
        nextChannelContext.setNext(null);
        newChannelContext.setIndex(index++);
        // 设置当前链表最后一个不为null的context节点指向下一个新增的节点
        currentChannelContext.setNext(nextChannelContext);

        // 持有的ChannelContext数量加1
        handlerCount++;
        return this;
    }

    public int getHandlerCount() {
        return handlerCount;
    }

    /**
     * 管道链处理
     *
     * @param socketChannelContext 当前通道绑定的应用上下文
     * @throws IOException
     */
    public void executeHandler(SocketChannelContext socketChannelContext) throws IOException {
        Object obj = socketChannelContext.getTransportDataModel();
        ChannelContext channelContext = this.headContext.next();
        while (null != channelContext) {
            AbstractChannelContext dc = (AbstractChannelContext) channelContext;
            // 设置 SocketChannelContext 引用
            dc.setSocketChannelContext(socketChannelContext);
            dc.getChannelHandler().handler(obj, dc);

            // 1、判断当前ChannelContext是否为终止ChannelContext, 是则跳出
            if (dc.getNeedStop()) {
                break;
            }

            // 2、判断当前ChannelContext是否需要跳过, 不需要则获取当前channelContext的下一个
            if (!dc.getNeedSkip()) {
                channelContext = dc.next();
                // 2.1、如果当前ChannelContext存在需要传递的handler数据，则将数据向下传递
                if (null != channelContext && null != dc.getObj()) {
                    // 由当前处理完成的Handler产生数据传递到下一个channelHandler
                    ((AbstractChannelContext) channelContext).setObj(dc.getObj());
                }
                continue;
            }

            // 需要跳过,则获取指定跳过数量后所指定的ChannelContext
            channelContext = ((SimpleChannelContext) channelContext).skip(dc.getNeedSkip(), dc, dc.getSkip());
        }

        // 一旦跳出while任务处理，重置各个任务handler状态，用于当前通道下次处理时已初始化的状态进行
        this.resetHandlerStatus();
    }

    /**
     * 重置handler任务跳过与终止状态
     */
    private void resetHandlerStatus() {
        ChannelContext channelContext = this.headContext.next();
        while (null != channelContext) {
            AbstractChannelContext dc = (AbstractChannelContext) channelContext;
            dc.setNeedSkip(Boolean.FALSE);
            dc.setNeedStop(Boolean.FALSE);
            channelContext = dc.next();
        }
    }

    /**
     * 通道链头上下文
     */
    private class HeadContext extends AbstractChannelContext {
        @Override
        public ChannelContext skip(Boolean isSkip, ChannelContext startChannelContext, int skip) {
            return null;
        }
    }
}
