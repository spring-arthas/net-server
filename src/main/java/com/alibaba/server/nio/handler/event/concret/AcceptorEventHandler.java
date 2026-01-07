package com.alibaba.server.nio.handler.event.concret;

import com.alibaba.server.common.BasicConstant;
import com.alibaba.server.nio.core.server.BasicServer;
import com.alibaba.server.nio.handler.event.AbstractEventHandler;
import com.alibaba.server.nio.model.ChannelEventModel;
import com.alibaba.server.nio.model.constant.ChannelEventModelEnum;

import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.LockSupport;

/**
 * @Auther: YSFY
 * @Date: 2020-11-21 12:05
 * @Pacage_name: com.alibaba.server.nio.Handler.concret
 * @Project_Name: net-server
 * @Description: 客户端监听[AcceptorEventHandler]
 */

@Slf4j
@SuppressWarnings("all")
public class AcceptorEventHandler extends AbstractEventHandler {

    private static Map<String, Object> map = null;

    static {
        map = (Map<String, Object>) BasicServer.getMap().get(BasicConstant.ACCEPTOR);
    }

    @Override
    public ChannelEventModel eventHandler(ChannelEventModel eventModel) {
        if(!super.checkEvent(eventModel)) {
            return eventModel;
        }

        if(!eventModel.getSelectionKey().isAcceptable()) {
            if(!Optional.ofNullable(super.getNextEventHandler()).isPresent()) {
                return eventModel;
            }

            return super.getNextEventHandler().eventHandler(eventModel);
        }

        map = (Map<String, Object>) BasicServer.getMap().get(BasicConstant.ACCEPTOR);
        return this.handler(eventModel);
    }

    /**
     * 执行处理, 唤醒对应的Acceptor线程
     * @param eventModel
     * @return eventModel
     * */
    private ChannelEventModel handler(ChannelEventModel eventModel) {
        Thread thread = null;

        if(!CollectionUtils.isEmpty(map)) {

            // 获取当前事件模型类型
            ChannelEventModelEnum eventModelEnum = ((Map<String, ChannelEventModelEnum>) BasicServer.getMap().get(BasicConstant.EVENT_TYPE)).get(eventModel.getEventModelEnum().getName());

            // 根据事件模型类型获取对应的Acceptor线程
            thread = (Thread) ((Map<String, Object>) map.get(eventModelEnum.getName())).get(BasicConstant.ACCEPTOR_THREAD);
        }

        if(Optional.ofNullable(thread).isPresent()) {
            LockSupport.unpark(thread);
        }

        return eventModel;
    }
}
