package com.alibaba.server.nio.handler.event;

import com.alibaba.server.nio.core.server.NioServerContext;
import com.alibaba.server.nio.model.ChannelCacheDataModel;
import com.alibaba.server.nio.model.EventModel;
import com.alibaba.server.nio.reactor.GlobalMainReactor;
import com.alibaba.server.nio.reactor.SubReactor;
import com.alibaba.server.util.LocalTime;
import lombok.extern.slf4j.Slf4j;

import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @Auther: YSFY
 * @Date: 2020-11-21 11:45
 * @Pacage_name: com.alibaba.server.nio.Handler
 * @Project_Name: net-server
 * @Description: 抽象的公共事件处理
 */

@Slf4j
@SuppressWarnings("all")
public abstract class AbstractEventHandler<T extends EventModel> implements EventHandler {
    /**
     * 通道缓存数据 key： 通道地址 value: 当前通道对应的缓存数据
     * */
    public static final Map<String, ChannelCacheDataModel> channelDataMap = new ConcurrentHashMap<>();

    /**
     * 指向下一个handler指针
     * */
    private AbstractEventHandler nextEventHandler;

    /**
     * 校验SelectionKey的合法性
     * @Param t
     * @return boolean
     * */
    public Boolean checkEvent(T t) {
        SelectionKey selectionKey = t.getSelectionKey();
        if(!Optional.ofNullable(selectionKey).isPresent()) {
            log.error("[" + LocalTime.formatDate(LocalDateTime.now()) + "] AbstractEventHandler | --> selectionKey is null");
            return Boolean.FALSE;
        }

        if(!selectionKey.isValid()) {
            log.error("[" + LocalTime.formatDate(LocalDateTime.now()) + "] AbstractEventHandler | --> selectionKey is Invalid");
            return Boolean.FALSE;
        }

        return Boolean.TRUE;
    }

    /**
     * 处理当前通道缓存数据，新加入通道则创建当前通道对应的缓存数据类ChannelCacheDataModel,如果存在
     * 则直接从缓存中获取
     * @param currentChannelEventModel
     * @return ChannelCacheDataModel
     */
    public final ChannelCacheDataModel cacheDataHandler(EventModel currentChannelEventModel) {
        ChannelCacheDataModel channelCacheDataModel = null;
        String currentChannelAddress = currentChannelEventModel.getRemoteAddress();
        if(!channelDataMap.containsKey(currentChannelAddress)) {
            channelCacheDataModel = new ChannelCacheDataModel();
            channelDataMap.put(currentChannelAddress, channelCacheDataModel);
        } else {
            channelCacheDataModel = channelDataMap.get(currentChannelAddress);
        }

        return channelCacheDataModel;
    }

    /**
     * 注册SubReactor
     * @param eventModel
     */
    public final void registerSubReactor(EventModel eventModel, String subReactor) {
        SubReactor reactor = GlobalMainReactor.registerStart(NioServerContext.getSelector(subReactor), ((SocketChannel) eventModel.getSelectionKey().channel()), subReactor);
        // 可用空间
        if(reactor.getQueue().remainingCapacity() > 0) {
            Map<String, Object> queueMap = new HashMap<>();
            queueMap.put("SOCKET_CHANNEL_CONTEXT",eventModel.getSelectionKey().attachment());
            queueMap.put("COMPLETE_LIST", eventModel.getCompleteList());
            reactor.getQueue().offer(queueMap);
        } else {
            log.warn("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] ReadEventHandler | --> chat socketChannel read cache queue is Full, avilableCount = {}, address = {}, thread = {}",
                reactor.getQueue().remainingCapacity(), NioServerContext.getRemoteAddress((SocketChannel) eventModel.getSelectionKey().channel()), Thread.currentThread().getName());
        }
    }

    public AbstractEventHandler getNextEventHandler() {
        return nextEventHandler;
    }

    public void setNextEventHandler(AbstractEventHandler nextEventHandler) {
        this.nextEventHandler = nextEventHandler;
    }
}
