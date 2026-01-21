package com.alibaba.server.nio.handler.event;

import com.alibaba.server.nio.model.ChannelEventDataCacheModel;
import com.alibaba.server.nio.model.ChannelEventModel;
import com.alibaba.server.util.LocalTime;
import lombok.extern.slf4j.Slf4j;

import java.nio.channels.SelectionKey;
import java.time.LocalDateTime;
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
public abstract class AbstractEventHandler<T extends ChannelEventModel> implements EventHandler {
    /**
     * 通道缓存数据 key： 通道地址 value: 当前通道对应的缓存数据
     */
    public static final Map<String, ChannelEventDataCacheModel> channelDataMap = new ConcurrentHashMap<>();

    /**
     * 指向下一个handler指针
     */
    private AbstractEventHandler nextEventHandler;

    /**
     * 校验SelectionKey的合法性
     * 
     * @Param t
     * @return boolean
     */
    public Boolean checkEvent(T t) {
        SelectionKey selectionKey = t.getSelectionKey();
        if (!Optional.ofNullable(selectionKey).isPresent()) {
            log.error("[" + LocalTime.formatDate(LocalDateTime.now())
                    + "] AbstractEventHandler | --> selectionKey is null");
            return Boolean.FALSE;
        }

        if (!selectionKey.isValid()) {
            log.error("[" + LocalTime.formatDate(LocalDateTime.now())
                    + "] AbstractEventHandler | --> selectionKey is Invalid");
            return Boolean.FALSE;
        }

        return Boolean.TRUE;
    }

    /**
     * 处理当前通道缓存数据，新加入通道则创建当前通道对应的缓存数据类ChannelCacheDataModel,如果存在
     * 则直接从缓存中获取
     * 
     * @param currentChannelEventModel
     * @return ChannelCacheDataModel
     */
    public final ChannelEventDataCacheModel createChannelEventModelCache(ChannelEventModel currentChannelEventModel) {
        ChannelEventDataCacheModel channelEventDataCacheModel = null;
        String remoteChannelAddress = currentChannelEventModel.getRemoteAddress();
        if (!channelDataMap.containsKey(remoteChannelAddress)) {
            channelEventDataCacheModel = new ChannelEventDataCacheModel();
            channelEventDataCacheModel.setChannelAddress(remoteChannelAddress);
            channelDataMap.put(remoteChannelAddress, channelEventDataCacheModel);
        } else {
            channelEventDataCacheModel = channelDataMap.get(remoteChannelAddress);
        }

        return channelEventDataCacheModel;
    }

    public AbstractEventHandler getNextEventHandler() {
        return nextEventHandler;
    }

    public void setNextEventHandler(AbstractEventHandler nextEventHandler) {
        this.nextEventHandler = nextEventHandler;
    }
}
