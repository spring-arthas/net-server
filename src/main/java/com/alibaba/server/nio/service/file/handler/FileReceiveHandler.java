package com.alibaba.server.nio.service.file.handler;

import com.alibaba.server.nio.handler.pipe.ChannelContext;
import com.alibaba.server.nio.service.api.AbstractChannelHandler;
import lombok.extern.slf4j.Slf4j;
import java.io.IOException;

/**
 * 文件读事件流数据处理器 --> 包括离线处理以及文件存储服务
 * 
 * @author spring
 */

@Slf4j
public class FileReceiveHandler extends AbstractChannelHandler {

    @Override
    public void handler(Object o, ChannelContext channelContext) throws IOException {
        /*
         * SocketChannelContext socketChannelContext = (SocketChannelContext) o;
         * List<Object> realList = socketChannelContext.getRealList();
         * 
         * // 1、处理文件数据(realList 中可能包含多个待处理文件)
         * for(Object obj : realList) {
         * FileMessageFrame fileMessageFrame = (FileMessageFrame) obj;
         * 
         * // 2、服务端开始接收客户端发送的文件数据，并执行存储
         * this.receiveFileData(fileMessageFrame, socketChannelContext, channelContext);
         * }
         */
    }
}
