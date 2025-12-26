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
 * 文件读事件真实数据处理器 --> 负责封装传送文件信息
 * 
 * @author spring
 */
@Slf4j
public class FileRealDataHandler extends AbstractChannelHandler {

    /**
     * @param o
     * @param channelContext
     * @throws IOException
     */
    @Override
    public void handler(Object o, ChannelContext channelContext) {
        Map<String, Object> map = (Map<String, Object>) o;
        SocketChannelContext socketChannelContext = (SocketChannelContext) map.get("SOCKET_CHANNEL_CONTEXT");

        // 1、处理文件数据(realList 中可能包含多个待处理文件)
        List<Object> realList = socketChannelContext.getTransportProtocol().getRealList();
        for (Object obj : realList) {
            FileMessageFrame fileMessageFrame = (FileMessageFrame) obj;

            // 1.1、在线传输帧处理
            if (fileMessageFrame.getFrameType().equals(FileMessageFrame.FrameType.ONLINE_TRANSPORT)) {
                FileTransportManager.realOnlineFileDataHandler(socketChannelContext, fileMessageFrame, channelContext);
                // 设置当前ChannelContext为终止Handler
                ((SimpleChannelContext) channelContext).setNeedStop(Boolean.TRUE);
            }

            // 1.2、上传帧处理 -> 分文件传输还是文件存储
            if (fileMessageFrame.getFrameType().equals(FileMessageFrame.FrameType.UPLOAD)) {
                FileTransportManager.realUploadFileDataHandler(socketChannelContext, fileMessageFrame, channelContext);
                // 设置当前ChannelContext为终止Handler
                ((SimpleChannelContext) channelContext).setNeedStop(Boolean.TRUE);
            }

            // 1.3、下载帧处理
            if (fileMessageFrame.getFrameType().equals(FileMessageFrame.FrameType.DOWNLOAD)) {
                Boolean result = FileTransportManager.realDownloadFileDataHandler(socketChannelContext,
                        fileMessageFrame, channelContext);
                if (result) {
                    ((SimpleChannelContext) channelContext).setNeedSkip(Boolean.TRUE).setSkip(3);
                } else {
                    ((SimpleChannelContext) channelContext).setNeedStop(Boolean.TRUE);
                }
            }
        }

        // 2、清空已处理的帧列表，防止内存泄漏和重复处理
        realList.clear();
        log.debug("已清空realList，防止内存泄漏");
    }
}
