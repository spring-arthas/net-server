package com.alibaba.server.nio.service.file.handler;

import com.alibaba.server.nio.core.server.BasicServer;
import com.alibaba.server.nio.handler.pipe.ChannelContext;
import com.alibaba.server.nio.model.SocketChannelContext;
import com.alibaba.server.nio.model.file.FileMessageFrame;
import com.alibaba.server.nio.repository.user.service.UserService;
import com.alibaba.server.nio.repository.user.service.dto.UserDTO;
import com.alibaba.server.nio.repository.user.service.param.UserQueryParam;
import com.alibaba.server.nio.service.api.AbstractChannelHandler;
import lombok.extern.slf4j.Slf4j;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 文件下载实时流处理
 * 
 * @author spring
 */

@Slf4j
@SuppressWarnings("all")
public class FileDownloadTransportStreamHandler extends AbstractChannelHandler {

    /**
     * @param o
     * @param channelContext
     * @throws IOException
     */
    @Override
    public void handler(Object o, ChannelContext channelContext) throws IOException {
        Map<String, Object> map = (Map<String, Object>) o;
        SocketChannelContext socketChannelContext = (SocketChannelContext) map.get("SOCKET_CHANNEL_CONTEXT");

        // 1、处理文件数据(realList 中可能包含多个待处理文件)
        List<Object> realList = socketChannelContext.getRealList();
        for (Object obj : realList) { // --> 每一个 [FileMessageFrame] 帧均为一个待下载的文件请求
            FileMessageFrame fileMessageFrame = (FileMessageFrame) obj;

            // 获取当前用户待发送的文件信息
            UserQueryParam userQueryParam = new UserQueryParam();
            userQueryParam.setUserName(fileMessageFrame.getFileTransport().getLaunchUserName());
            UserDTO userDto = ((UserService) BasicServer.classPathXmlApplicationContext.getBean(UserService.class))
                    .getOnlineUser(userQueryParam);
            Map<String, Object> currentDownloadFileMap = userDto.getDownloadFileMap()
                    .get(fileMessageFrame.getFileTransport().getTag());

            // 开启异步线程直接发送文件
            super.addFileDownloadTask(currentDownloadFileMap, socketChannelContext);
        }
    }
}
