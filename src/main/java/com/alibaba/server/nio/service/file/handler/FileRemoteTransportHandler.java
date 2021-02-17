package com.alibaba.server.nio.service.file.handler;

import com.alibaba.server.common.BasicConstant;
import com.alibaba.server.nio.core.server.BasicServer;
import com.alibaba.server.nio.core.server.NioServerContext;
import com.alibaba.server.nio.handler.event.concret.WriteEventHandler;
import com.alibaba.server.nio.handler.pipe.ChannelContext;
import com.alibaba.server.nio.handler.pipe.standard.SimpleChannelContext;
import com.alibaba.server.nio.model.SocketChannelContext;
import com.alibaba.server.nio.model.file.FileMessageFrame;
import com.alibaba.server.nio.reactor.SubReactor;
import com.alibaba.server.nio.repository.task.service.TaskService;
import com.alibaba.server.nio.repository.task.service.param.TaskCreateParam;
import com.alibaba.server.nio.repository.task.service.param.TaskQueryParam;
import com.alibaba.server.nio.repository.user.service.UserService;
import com.alibaba.server.nio.repository.user.service.dto.UserDTO;
import com.alibaba.server.nio.service.api.AbstractChannelHandler;
import com.alibaba.server.util.LocalTime;
import lombok.extern.slf4j.Slf4j;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 文件远程传送数据处理器 --> 负责离线或是在线通知远程客户端接收传送文件
 * @author spring
 * */

@Slf4j
public class FileRemoteTransportHandler extends AbstractChannelHandler {

    @Override
    public void handler(Object o, ChannelContext channelContext) throws IOException {
        /*SocketChannelContext socketChannelContext = (SocketChannelContext) o;
        SimpleChannelContext simpleChannelContext = (SimpleChannelContext) channelContext;
        Map<String, Object> remoteMessageMap = (Map<String, Object>) simpleChannelContext.getObj();

        // 1、 文件传送任务存储DB
        this.storeTaskToDB(remoteMessageMap);*/

    }


    /**
     * 向远程客户端推送文件传送通知
     * @param remoteMessageMap
     * @param socketChannelContext
     */
    private void sendBeginTransportFileToRemoteUserFrame(Map<String, Object> remoteMessageMap, SocketChannelContext socketChannelContext) {
        Map<String, Object> map = new HashMap<>(8);
        map.put("code", 200);
        map.put("frameType", FileMessageFrame.FileOperateType.TRANSPORT.getBit());
        map.put("message", "待处理通知: 您有一条来自 [ " + remoteMessageMap.get("currentUserName") + " ] 传送的文件 [ " + remoteMessageMap.get("fileName") + " ]");
        map.put("fileName", remoteMessageMap.get("fileName").toString());
        map.put("fileSize", Long.parseLong(remoteMessageMap.get("fileSize").toString()));
        map.put("time", BasicConstant.SDF.format(new Date()));

        log.info("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] FileRemoteTransportHandler | --> send file transport task notice success, remoteName = {}, thread = {}",
            remoteMessageMap.get("remoteUserName").toString(), Thread.currentThread().getName());
        WriteEventHandler.addSendData(map, socketChannelContext);
    }

    /**
     * 存储任务
     * @param remoteMessageMap
     */
    private void storeTaskToDB(Map<String, Object> remoteMessageMap) {
        TaskService taskService = (TaskService) BasicServer.classPathXmlApplicationContext.getBean(TaskService.class);
        TaskCreateParam taskCreateParam = new TaskCreateParam();
        taskCreateParam.setTaskName("待处理通知: 您有一条来自 [ " + remoteMessageMap.get("currentUserName") + " ] 传送的文件 [ " + remoteMessageMap.get("fileName") + " ]");
        taskCreateParam.setStatus("1");
        taskCreateParam.setSponsorUser(remoteMessageMap.get("currentUserName").toString());
        taskCreateParam.setReceiveUser(remoteMessageMap.get("remoteUserName").toString());
        taskCreateParam.setFileName(remoteMessageMap.get("fileName").toString());
        taskCreateParam.setFileSize(Long.parseLong(remoteMessageMap.get("fileSize").toString()));
        taskCreateParam.setFilePath(remoteMessageMap.get("filePath").toString());
        taskService.create(taskCreateParam);

        log.info("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] FileRemoteTransportHandler | --> remote user is offline，store DB task success, task = {}, remoteUser = {}, thread = {}",
            taskCreateParam.getReceiveUser(), taskCreateParam.getReceiveUser(), Thread.currentThread().getName());
    }
}
