package com.alibaba.server.nio.selector;

import com.alibaba.server.common.BasicConstant;
import com.alibaba.server.nio.core.server.NioServerContext;
import com.alibaba.server.nio.handler.event.EventHandlerContext;
import com.alibaba.server.nio.model.ChannelEventModel;
import com.alibaba.server.util.LocalTime;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.exception.ExceptionUtils;

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
 *               在线文件发送或是上传: 发起者向10087端口建立连接开始进行文件的操作，首先是对文件进行验证
 *
 */

@Slf4j
@SuppressWarnings("all")
public class MainFileSelector extends AbstractSelector implements Runnable {

    @Override
    public void run() {
        Selector selector = super.getCheck(BasicConstant.NIO_SERVER_MAIN_CORE_FILE_SELECTOR);
        Integer SELECTOR_POLL_TIMEOUT = Integer
                .parseInt(NioServerContext.getValue(BasicConstant.SELECTOR_POLL_TIMEOUT));
        while (true) {
            try {
                if (selector.select(SELECTOR_POLL_TIMEOUT) == 0) {
                    TimeUnit.MILLISECONDS.sleep(10);
                    continue;
                }
                // 遍历可选事件进行处理
                Iterator iterator = selector.selectedKeys().iterator();
                while (iterator.hasNext()) {
                    ChannelEventModel channelEventModel = new ChannelEventModel();
                    this.setEventModel(channelEventModel, (SelectionKey) iterator.next());
                    iterator.remove();
                    // 执行数据处理，写事件处理走WriteEventHandler，读事件处理走ReadEventHandler
                    EventHandlerContext.getEventHandlerContext().execute(channelEventModel);
                }
            } catch (Exception e) {
                log.error("MainFileSelector事件处理：多路复用处理错误, error = {}", ExceptionUtils.getStackTrace(e));
            }
        }
    }
}
