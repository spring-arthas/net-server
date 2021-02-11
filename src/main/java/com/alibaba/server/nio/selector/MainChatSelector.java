package com.alibaba.server.nio.selector;

import com.alibaba.server.common.BasicConstant;
import com.alibaba.server.nio.handler.event.EventHandlerContext;
import com.alibaba.server.nio.core.server.NioServerContext;
import com.alibaba.server.nio.model.EventModel;
import com.alibaba.server.nio.model.constant.EventModelEnum;
import com.alibaba.server.util.LocalTime;
import lombok.extern.slf4j.Slf4j;

import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

/**
 * @Auther: YSFY
 * @Date: 2020-11-21 10:38
 * @Pacage_name: com.alibaba.server.nio.selector
 * @Project_Name: net-server
 * @Description: 聊天Selector
 */

@Slf4j
@SuppressWarnings("all")
public class MainChatSelector extends AbstractSelector implements Runnable {

    @Override
    public void run() {
        // 1、获取Selector
        Selector selector = super.getCheck(BasicConstant.NIO_SERVER_MAIN_CORE_CHAT_SELECTOR);

        // 2、启动Selector轮询
        Integer SELECTOR_POLL_TIMEOUT = Integer.parseInt(NioServerContext.getValue(BasicConstant.SELECTOR_POLL_TIMEOUT));
        while(true) {
            try {
                if(selector.select(SELECTOR_POLL_TIMEOUT) == 0) {
                    TimeUnit.MILLISECONDS.sleep(1);
                    continue;
                }

                Iterator iterator = selector.selectedKeys().iterator();
                while (iterator.hasNext()) {
                    EventModel eventModel = new EventModel();
                    SelectionKey selectionKey = (SelectionKey) iterator.next();
                    eventModel.setSelectionKey(selectionKey);
                    if(selectionKey.channel() instanceof SocketChannel) {
                        eventModel.setRemoteAddress(NioServerContext.getRemoteAddress(((SocketChannel) selectionKey.channel())));
                    }
                    eventModel.setEventModelEnum(EventModelEnum.CHAT_TASK);
                    iterator.remove();

                    // 执行处理
                    EventHandlerContext.getEventHandlerContext().execute(eventModel);
                }
            } catch (Exception e) {
                log.error("[" + LocalTime.formatDate(LocalDateTime.now()) + "] MainChatSelector | --> selector handle selectionKey error, error = {}", e.getMessage());
                e.printStackTrace();
            }
        }
    }
}
