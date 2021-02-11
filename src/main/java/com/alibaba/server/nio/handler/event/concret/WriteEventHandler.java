package com.alibaba.server.nio.handler.event.concret;

import com.alibaba.fastjson.JSON;
import com.alibaba.server.common.BasicConstant;
import com.alibaba.server.nio.core.server.BasicServer;
import com.alibaba.server.nio.core.server.NioServerContext;
import com.alibaba.server.nio.handler.event.AbstractEventHandler;
import com.alibaba.server.nio.handler.worker.WorkerThreadPool;
import com.alibaba.server.nio.model.EventModel;
import com.alibaba.server.nio.model.SocketChannelContext;
import com.alibaba.server.nio.model.chat.ChatMessageFrame;
import com.alibaba.server.nio.model.constant.EventModelEnum;
import com.alibaba.server.nio.repository.file.service.dto.FileDto;
import com.alibaba.server.nio.repository.user.service.dto.UserDTO;
import com.alibaba.server.nio.service.chat.model.ChatMessageLogout;
import com.alibaba.server.nio.service.file.util.FileWriteEventParseUtil;
import com.alibaba.server.util.BasicUtil;
import com.alibaba.server.util.LocalTime;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.springframework.util.CollectionUtils;

import java.awt.datatransfer.SystemFlavorMap;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * @Auther: YSFY
 * @Date: 2020-11-21 12:08
 * @Pacage_name: com.alibaba.server.nio.Handler.concret
 * @Project_Name: net-server
 * @Description: 写事件处理[WriteEventHandler]
 */

@Slf4j
@SuppressWarnings("all")
public class WriteEventHandler extends AbstractEventHandler {
    private static Integer loop = 0;
    private static Integer maxWriteWaitingCount = 0;
    private static Integer everyWriteEventHandlerTime = 0;

    public WriteEventHandler() {
        maxWriteWaitingCount = Integer.valueOf(BasicServer.getMap().get(BasicConstant.WRITE_MAX_WAIT_COUNT).toString());
        everyWriteEventHandlerTime = Integer.valueOf(BasicServer.getMap().get(BasicConstant.SELECTOR_WRITE_EVENT_HANDLER_TIME).toString());
    }

    @Override
    public EventModel eventHandler(EventModel eventModel) {
        if(!super.checkEvent(eventModel)) {
            return eventModel;
        }

        if(!eventModel.getSelectionKey().isWritable()) {
            if(!Optional.ofNullable(super.getNextEventHandler()).isPresent()) {
                return eventModel;
            }
            return super.getNextEventHandler().eventHandler(eventModel);
        }

        return this.handler(eventModel);
    }

    /**
     * 执行处理
     * @param eventModel
     * @return eventModel
     * */
    private EventModel handler(EventModel eventModel) {
        SelectionKey selectionKey = eventModel.getSelectionKey();
        SocketChannelContext socketChannelContext = (SocketChannelContext) eventModel.getSelectionKey().attachment();
        LinkedBlockingQueue<Object> linkedBlockingQueue = ((LinkedBlockingQueue) socketChannelContext.getBlockingQueue());
        if(!CollectionUtils.isEmpty(linkedBlockingQueue)) {
            while (linkedBlockingQueue.size() > 0) {
                FileWriteEventParseUtil.parseMessageAndSend((SocketChannel) selectionKey.channel(), linkedBlockingQueue.poll(), socketChannelContext);
            }
        } else {
            loop++;

            if(loop.equals(maxWriteWaitingCount) ) {
                try {
                    TimeUnit.MILLISECONDS.sleep(10);
                    loop = 0;
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }

        return eventModel;
    }

    /**
     * 追加待发送数据到通道附件
     * @param map 发送数据对象
     * @param o 当前通道的附件对象
     */
    public static void addSendData(Map map, Object o) {
        SocketChannelContext socketChannelContext = (SocketChannelContext) o;
        LinkedBlockingQueue linkedBlockingQueue = ((LinkedBlockingQueue) socketChannelContext.getBlockingQueue());
        if(linkedBlockingQueue.remainingCapacity() > 0) {
            linkedBlockingQueue.offer(map);
        }
    }
}
