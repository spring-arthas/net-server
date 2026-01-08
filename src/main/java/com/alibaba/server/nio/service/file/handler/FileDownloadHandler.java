package com.alibaba.server.nio.service.file.handler;

import com.alibaba.server.nio.handler.pipe.ChannelContext;
import com.alibaba.server.nio.handler.pipe.standard.SimpleChannelContext;
import com.alibaba.server.nio.model.SocketChannelContext;
import com.alibaba.server.nio.model.file.FileMessageFrame;
import com.alibaba.server.nio.service.api.AbstractChannelHandler;
import com.alibaba.server.nio.service.file.operate.FileTransportManager;
import lombok.extern.slf4j.Slf4j;
import java.io.IOException;
import java.util.*;

/**
 * 文件下载处理器
 * 
 * @author spring
 */
@Slf4j
public class FileDownloadHandler extends AbstractChannelHandler {

    /**
     * @param o
     * @param channelContext
     * @throws IOException
     */
    @Override
    public void handler(Object o, ChannelContext channelContext) {
    }
}
