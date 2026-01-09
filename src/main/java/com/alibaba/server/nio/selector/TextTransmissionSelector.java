package com.alibaba.server.nio.selector;

import com.alibaba.server.common.BasicConstant;
import com.alibaba.server.nio.handler.event.EventHandlerContext;
import com.alibaba.server.nio.core.server.NioServerContext;
import com.alibaba.server.nio.model.ChannelEventModel;
import com.alibaba.server.util.LocalTime;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.exception.ExceptionUtils;

import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

/**
 * 文本传输选择器(包含聊天和网盘文件夹处理)
 * @Auther: duyao
 * @Date: 2020-11-21 10:38
 * @Pacage_name: com.alibaba.server.nio.selector
 * @Project_Name: net-server
 * @Description: TextTransmissionSelector
 */

@Slf4j
@SuppressWarnings("all")
public class TextTransmissionSelector extends AbstractSelector implements Runnable {

    @Override
    public void run() {
        // 1、获取Selector
        Selector selector = super.getCheck(BasicConstant.NIO_SERVER_MAIN_CORE_TEXT_SELECTOR);

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
                    ChannelEventModel channelEventModel = new ChannelEventModel();
                    this.setEventModel(channelEventModel, (SelectionKey) iterator.next());
                    iterator.remove();
                    // 执行处理
                    EventHandlerContext.getEventHandlerContext().execute(channelEventModel);
                }
            } catch (Exception e) {
                log.error("TextTransmissionSelector事件处理：多路复用处理错误, error = {}", ExceptionUtils.getStackTrace(e));
            }
        }
    }
}
