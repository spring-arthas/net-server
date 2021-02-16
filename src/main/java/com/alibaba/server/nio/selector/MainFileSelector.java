package com.alibaba.server.nio.selector;

import com.alibaba.server.common.BasicConstant;
import com.alibaba.server.nio.core.server.NioServerContext;
import com.alibaba.server.nio.handler.event.EventHandlerContext;
import com.alibaba.server.nio.model.EventModel;
import com.alibaba.server.util.LocalTime;
import lombok.extern.slf4j.Slf4j;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

/**
 * @Auther: YSFY
 * @Date: 2020-11-21 10:40
 * @Pacage_name: com.alibaba.server.nio.selector
 * @Project_Name: net-server
 * @Description: 文件 Selector
 *
 *    在线文件发送或是上传: 发起者向10087端口建立连接开始进行文件的操作，首先是对文件进行验证
 *
 */

@Slf4j
@SuppressWarnings("all")
public class MainFileSelector extends AbstractSelector implements Runnable {

    @Override
    public void run() {
        // 1、获取Selector
        Selector selector = super.getCheck(BasicConstant.NIO_SERVER_MAIN_CORE_FILE_SELECTOR);

        // 2、启动Selector轮询
        Integer SELECTOR_POLL_TIMEOUT = Integer.parseInt(NioServerContext.getValue(BasicConstant.SELECTOR_POLL_TIMEOUT));
        while(true) {
            try {
                if(selector.select(SELECTOR_POLL_TIMEOUT) == 0) {
                    TimeUnit.MILLISECONDS.sleep(10);
                    continue;
                }

                // 包含多个已就绪的socketChannel通道描述符
                Iterator iterator = selector.selectedKeys().iterator();
                while (iterator.hasNext()) {
                    // 获取当前SelectionKey通道附件SocketChannelContext，一个通道不管触发订阅的任何事件，都共用该一个SocketChannelContext
                    EventModel eventModel = new EventModel();
                    super.setEventModel(eventModel, (SelectionKey) iterator.next());
                    iterator.remove();
                    EventHandlerContext.getEventHandlerContext().execute(eventModel);
                }
            } catch (Exception e) {
                log.error("[" + LocalTime.formatDate(LocalDateTime.now()) + "] MainFileSelector | --> selector handle selectionKey error, error = {}", e.getMessage());
                e.printStackTrace();
            } finally {

            }
        }
    }
}
