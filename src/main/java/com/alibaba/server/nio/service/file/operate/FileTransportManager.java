package com.alibaba.server.nio.service.file.operate;

import com.alibaba.fastjson.JSON;
import com.alibaba.server.common.BasicConstant;
import com.alibaba.server.nio.core.server.BasicServer;
import com.alibaba.server.nio.core.server.NioServerContext;
import com.alibaba.server.nio.handler.event.concret.WriteEventHandler;
import com.alibaba.server.nio.handler.pipe.ChannelContext;
import com.alibaba.server.nio.handler.pipe.standard.SimpleChannelContext;
import com.alibaba.server.nio.model.SocketChannelContext;
import com.alibaba.server.nio.model.chat.ChatMessageFrame;
import com.alibaba.server.nio.model.file.FileMessageFrame;
import com.alibaba.server.nio.reactor.GlobalMainReactor;
import com.alibaba.server.nio.reactor.SubReactor;
import com.alibaba.server.nio.repository.file.service.FileService;
import com.alibaba.server.nio.repository.file.service.dto.FileDto;
import com.alibaba.server.nio.repository.file.service.param.FileQueryParam;
import com.alibaba.server.nio.repository.user.service.UserService;
import com.alibaba.server.nio.repository.user.service.dto.UserDTO;
import com.alibaba.server.nio.repository.user.service.param.UserQueryParam;
import com.alibaba.server.nio.service.api.AbstractChannelHandler;
import com.alibaba.server.nio.service.file.model.FileOperateEnum;
import com.alibaba.server.nio.service.file.model.FileTransport;
import com.alibaba.server.nio.service.file.util.FileWriteEventParseUtil;
import com.alibaba.server.util.LocalTime;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.springframework.util.CollectionUtils;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.StandardOpenOption;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文件在线传输实时处理管理器
 * @author spring
 * */
@Slf4j
public class FileTransportManager {
    private static String basePath = "";
    private static UserService userService = null;
    private static FileService fileService = null;
    static {
        if(StringUtils.contains(BasicServer.getMap().get(BasicConstant.OS_NAME).toString(), "Win")) {
            basePath = BasicServer.getMap().get(BasicConstant.NIO_FILE_BASE_PATH_WINDOWS).toString();
        } else {
            basePath = BasicServer.getMap().get(BasicConstant.NIO_FILE_BASE_PATH_LINUX_MAC).toString();
        }

        userService = BasicServer.classPathXmlApplicationContext.getBean(UserService.class);
        fileService = BasicServer.classPathXmlApplicationContext.getBean(FileService.class);
    }

    /**
     * 文件在线传输处理
     * @param socketChannelContext 在线文件接收用户SocketChannel通道附件
     * @param fileMessageFrame 在线文件传输是否接收帧 --> 由文件接收端用户端发起
     * @param channelContext  上下文
     * @return 文件在线传输结果
     */
    public static void realOnlineFileDataHandler(SocketChannelContext socketChannelContext, FileMessageFrame fileMessageFrame, ChannelContext channelContext) {
        // 1、查询发起端和接收端用户
        UserQueryParam userQueryParam = new UserQueryParam();

        // 2、解析文件在线传输接收端响应帧，判断远程用户是否同意在线文件传输
        FileTransport fileTransport = JSON.parseObject(fileMessageFrame.getData(), FileTransport.class);
        fileMessageFrame.setFileTransport(fileTransport);
        if (!fileTransport.getIsConfirmReceive()) {
            // 远程用户拒绝接收 --> 向在线文件发起用户发送远程用户取消了在线接收的消息，此时发起用户客户端关闭文件文件连接
            userQueryParam.setUserName(fileTransport.getLaunchUserName());
            SubReactor subReactor = GlobalMainReactor.getSubReactorForSocketChannel(BasicServer.classPathXmlApplicationContext.getBean(UserService.class).getOnlineUser(userQueryParam).getFileSocketChannel());
            sendMessage(fileMessageFrame.getFileTransport(), subReactor.getSocketChannelContext(), FileMessageFrame.FrameType.ONLINE_TRANSPORT.getBit(), FileOperateEnum.ONLINE_TRANSPORT_CANCEL);
        }

        userQueryParam.setUserName(fileMessageFrame.getFileTransport().getLaunchUserName());
        UserDTO launchUserDto = BasicServer.classPathXmlApplicationContext.getBean(UserService.class).getOnlineUser(userQueryParam);
        userQueryParam.setUserName(fileMessageFrame.getFileTransport().getReceiveUserName());
        UserDTO receiveUserDto = BasicServer.classPathXmlApplicationContext.getBean(UserService.class).getOnlineUser(userQueryParam);
        receiveUserDto.setFileSocketChannel(socketChannelContext.getTransportProtocol().getSocketChannel());

        // 3、向发起端用户发送开始传送文件流的通知
        sendMessage(fileTransport, GlobalMainReactor.getSubReactorForSocketChannel(launchUserDto.getFileSocketChannel()).getSocketChannelContext(), FileMessageFrame.FrameType.ONLINE_TRANSPORT.getBit(), FileOperateEnum.ONLINE_TRANSPORT_CONFIRM);
    }

    /**
     * 文件上传帧数据处理 区分是文件传送还是文件存储
     * @param socketChannelContext 文件在线传输或是存储发起者对应的socketChannel通道附件
     * @param fileMessageFrame
     * @param channelContext
     */
    public static void realUploadFileDataHandler(SocketChannelContext socketChannelContext, FileMessageFrame fileMessageFrame, ChannelContext channelContext) {
        // 1、解析文件在线传输帧
        FileTransport fileTransport = JSON.parseObject(fileMessageFrame.getData(), FileTransport.class);
        fileMessageFrame.setFileTransport(fileTransport);

        // 2、文件聊天传输 --> 分在线和离线传输
        if(fileMessageFrame.getFileOperateType().equals(FileMessageFrame.FileOperateType.TRANSPORT)) {
            // 2.1、获取用户服务类
            UserQueryParam userQueryParam = new UserQueryParam();
            UserService userService = (UserService) BasicServer.classPathXmlApplicationContext.getBean(UserService.class);

            // 2.2、查询接收用户: 判断是否在线，在线执行远程发送，不在线执行离线传输
            userQueryParam.setUserName(fileMessageFrame.getFileTransport().getReceiveUserName());
            UserDTO receiveUserDto = userService.getOnlineUser(userQueryParam);
            if(!Optional.ofNullable(receiveUserDto).isPresent()) {
                // 2.1.1、用户不在线那么封装文件信息做离线处理
                //fileStoreHandler(socketChannelContext, fileMessageFrame, basePath, delimiter);

                // 2.1.2、用户不在线，执行离线传输,跳过下一个ChannelContext,直接存储文件即可 --> 对应处理器为FileReceiveHandler
                ((SimpleChannelContext) channelContext).setNeedSkip(Boolean.TRUE).setSkip(1).setObj(receiveUserDto);
                return;
            }

            // 2.3、查询发起用户: 并设置其对应的文件通道
            userQueryParam.setUserName(fileMessageFrame.getFileTransport().getLaunchUserName());
            UserDTO launchUserDto = userService.getOnlineUser(userQueryParam);
            launchUserDto.setFileSocketChannel(socketChannelContext.getTransportProtocol().getSocketChannel());

            // 2.4、向远程接收用户发送接收文件的消息，由用户判断是否需要接收传送或是放弃；此处需要远程用户确定要接收，并且建立新的socketChannel连接用于接收文件
            Map<String, Object> map = new HashMap<>(8);
            map.put("code", 200);
            map.put("frameType", ChatMessageFrame.FrameType.TEXT.getBit());
            map.put("message", "待处理通知: 您有一条来自用户 [ " + launchUserDto.getUserName() + " ] 传送的文件 [ " + fileMessageFrame.getFileTransport().getFileName() + " ]");
            map.put("messageType", FileOperateEnum.ONLINE_TRANSPORT_NEED_CONFIRM.getOperate());
            map.put("fileName", fileMessageFrame.getFileTransport().getFileName());
            map.put("fileSize", Long.parseLong(fileMessageFrame.getFileLength().toString()));
            map.put("launchUserName", launchUserDto.getUserName());
            map.put("receiveUserName", receiveUserDto.getUserName());
            map.put("operate", FileOperateEnum.ONLINE_TRANSPORT_NEED_CONFIRM.getOperate());
            map.put("status", "1");
            map.put("time", Timestamp.valueOf(LocalDateTime.now()));

            log.info("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] FileTransportManager | --> 接收用户 = [{}] 接收到一条来自用户 = [{}] 发送的在线文件实时传输, 需要接收端用户确认是否接收, thread = {}",
                receiveUserDto.getUserName(), launchUserDto.getUserName(), Thread.currentThread().getName());
            WriteEventHandler.addSendData(map, GlobalMainReactor.getSubReactorForSocketChannel(receiveUserDto.getChatSocketChannel()).getSocketChannelContext());
        }

        // 3、文件存储 --> 服务端负责进行文件存储服务 --> 对应处理器为FileReceiveHandler
        if(fileMessageFrame.getFileOperateType().equals(FileMessageFrame.FileOperateType.STORE)) {
            fileStoreHandler(socketChannelContext, fileMessageFrame, basePath);
        }
    }

    /**
     * 文件上传处理
     * @param socketChannelContext
     * @param fileMessageFrame
     * @param basePath 文件上传根路径
     */
    private static void fileStoreHandler(SocketChannelContext socketChannelContext, FileMessageFrame fileMessageFrame, String basePath) {
        FileTransport fileTransport = fileMessageFrame.getFileTransport();

        // 1、如果在个人网盘存储业务处理中接收到了需要关闭文件通道的操作，则关闭当前文件通道(可能由于客户端临时取消了文件上传)
        if(StringUtils.contains(fileTransport.getOperate(), BasicConstant.CLOSE_FILE_CHANNLE)) {
            AbstractChannelHandler.checkFileTransportStop(fileTransport);
            return;
        }

        // 2、判断当前待上传路径下是否存在相同的文件
        File executeFile = new File(basePath + fileTransport.getGroup() + fileTransport.getFileName());
        if(executeFile.exists()){
            log.info("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] FileTransportManager | --> 待上传文件 = [{}] 校验完成，当前目录下 = [{}] 已存在, 请修改文件名称后上传, thread = {}",
                fileTransport.getFileName(), fileTransport.getGroup(), Thread.currentThread().getName());

            // 文件存在，终止上传
            Map<String, Object> map = new HashMap<>(8);
            map.put("code", 200);
            map.put("FILE_FRAME", BasicConstant.FILE_FRAME);
            map.put("frameType", FileMessageFrame.FrameType.UPLOAD_TRANSPORT_CONFIRM.getBit());
            map.put("message", "[ " + fileMessageFrame.getFileTransport().getFileName() + " ] 校验完成, 当前目录下 [" + fileTransport.getGroup() + "] 已存在, 请修改文件名称后上传");
            map.put("operate", FileOperateEnum.UPLOAD_TRANSPORT_CONFIRM_REPEAT.getOperate());
            map.put("status", "1");
            map.put("time", Timestamp.valueOf(LocalDateTime.now()));
            WriteEventHandler.addSendData(map, socketChannelContext);
            return;
        }

        // 3、获取当前执行上传的
        UserQueryParam userQueryParam = new UserQueryParam();
        userQueryParam.setUserName(fileTransport.getLaunchUserName());
        UserDTO userDto = userService.getOnlineUser(userQueryParam);
        if(null == userDto.getUploadFileMap()) {
            userDto.setUploadFileMap(new ConcurrentHashMap<>());
        }

        // 4、构建文件及通道以及向客户端发送开始上传文件的通知
        try {
            // 创建临时文件
            executeFile.createNewFile();

            // 配置当前上传文件信息
            Map<String, Map<String, Object>> fileMap = userDto.getUploadFileMap();
            if(!fileMap.containsKey(fileTransport.getTag())) {
                Map<String, Object> map = new HashMap<String, Object>();
                map.put("FILE", executeFile);
                map.put("FILE_LENGTH", fileTransport.getFileSize());
                map.put("FILE_CHANNEL", FileChannel.open(executeFile.toPath(), StandardOpenOption.CREATE , StandardOpenOption.WRITE));
                fileMap.put(fileTransport.getTag(), map);

                log.info("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] FileTransportManager | --> 文件 [{}] 上传通道创建成功, 上传路径 = [{}], thread = {}", fileTransport.getFileName(), executeFile.toPath().toAbsolutePath(), Thread.currentThread().getName());
            }

            // 发送文件开始上传通知
            Map<String, Object> map = new HashMap<>(8);
            map.put("code", 200);
            map.put("FILE_FRAME", BasicConstant.FILE_FRAME);
            map.put("frameType", FileMessageFrame.FrameType.UPLOAD_TRANSPORT_CONFIRM.getBit());
            map.put("message", "[ " + fileMessageFrame.getFileTransport().getFileName() + " ] 校验完成, 开始进行文件传输");
            map.put("operate", FileOperateEnum.UPLOAD_TRANSPORT_CONFIRM.getOperate());
            map.put("status", "1");
            map.put("time", Timestamp.valueOf(LocalDateTime.now()));

            log.info("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] FileTransportManager | --> 待上传文件 = [{}] 校验完成，允许客户端 = [{}] 发送文件数据, thread = {}",
                fileTransport.getFileName(), fileTransport.getLaunchUserName(), Thread.currentThread().getName());
            WriteEventHandler.addSendData(map, socketChannelContext);
        } catch (IOException e) {
            log.error("[" + LocalTime.formatDate(LocalDateTime.now()) + "] FileTransportManager | --> 文件 [{}] 上传帧处理 error, path = {}, error = {}", fileTransport.getFileName(), executeFile.toPath().toAbsolutePath(), e.getMessage());
            e.printStackTrace();
        }
    }


    /**
     * 文件下载帧处理
     * @param socketChannelContext 文件在线传输或是存储发起者对应的socketChannel通道附件
     * @param fileMessageFrame
     * @param channelContext
     * @return
     */
    public static Boolean realDownloadFileDataHandler(SocketChannelContext socketChannelContext, FileMessageFrame fileMessageFrame, ChannelContext channelContext) {
        // 1、解析文件在线传输帧
        FileTransport fileTransport = JSON.parseObject(fileMessageFrame.getData(), FileTransport.class);
        fileMessageFrame.setFileTransport(fileTransport);

        // 2、如果在个人网盘存储业务处理中接收到了需要关闭文件通道的操作，则关闭当前文件通道(可能由于客户端临时取消了文件下载)
        if(StringUtils.contains(fileTransport.getOperate(), BasicConstant.CLOSE_FILE_CHANNLE)) {
            AbstractChannelHandler.checkFileTransportStop(fileTransport);
            return Boolean.FALSE;
        }

        // 3、DB中查询待下载的文件记录
        FileQueryParam fileQueryParam = new FileQueryParam();
        fileQueryParam.setId(Long.valueOf(fileTransport.getTag()));
        FileDto fileDto = fileService.getFileById(fileQueryParam);
        if(fileDto == null) {
            // DB中为查询出文件信息，发送文件不存在消息
            log.info("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] FileTransportManager | --> 待下载文件 = [{}] 校验完成，DB不存在文件记录, thread = {}",
                fileTransport.getFileName(), Thread.currentThread().getName());
            String message = "[ " + fileMessageFrame.getFileTransport().getFileName() + " ] DB不存在该文件记录, 下载失败";
            WriteEventHandler.addSendData(createDownloadFileErrorMap(message, FileOperateEnum.DOWNLOAD_TRANSPORT_CONFIRM_NOT_EXIST.getOperate()), socketChannelContext);
            return Boolean.FALSE;
        }

        // 4、待下载文件是否真实存在
        File executeFile = new File(basePath + fileDto.getFilePath());
        if(!executeFile.exists()){
            // 数据库存在文件记录但是盘符不存在
            log.info("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] FileTransportManager | --> 待上传文件 = [{}] 校验完成，文件系统中不存在, thread = {}",
                fileTransport.getFileName(), Thread.currentThread().getName());

            String message = "[ " + fileMessageFrame.getFileTransport().getFileName() + " ] 服务器文件系统中不存在, 下载失败";
            WriteEventHandler.addSendData(createDownloadFileErrorMap(message, FileOperateEnum.DOWNLOAD_TRANSPORT_CONFIRM_NOT_EXIST.getOperate()), socketChannelContext);
            return Boolean.FALSE;
        }

        // 5、获取当前用户文件下载集合
        UserQueryParam userQueryParam = new UserQueryParam();
        userQueryParam.setUserName(fileTransport.getLaunchUserName());
        UserDTO userDto = userService.getOnlineUser(userQueryParam);
        if(null == userDto.getDownloadFileMap()) {
            userDto.setDownloadFileMap(new ConcurrentHashMap<>());
        }

        // 5、构建文件及通道以及向客户端发送开始下载文件的通知
        try {
            // 配置当前下载文件信息,计算文件下载时，进度条需要循环的次数
            // 文件总大小
            long fileSize = executeFile.length();
            // 文件每次读取的大小
            long readSize = Integer.parseInt(NioServerContext.getValue(BasicConstant.DOWNLOAD_SEND_BYTE_BUFFER));
            long loopCount = 0;
            if(fileSize < readSize) {
                loopCount = 1;
            } else {
                // 总循环次数
                loopCount = fileSize / readSize;
                // 计算余数，如果余数大于0， 总循环次数 + 1
                long rest = fileSize % readSize;
                if(rest > 0) {
                    loopCount = loopCount + 1;
                }
            }

            // 当前用户添加文件下载任务
            if(userDto.getDownloadFileMap().containsKey(fileTransport.getTag())) {
                userDto.getDownloadFileMap().remove(fileTransport.getTag());
            }
            Map<String, Object> downloadMap = new HashMap<String, Object>();
            downloadMap.put("TAG", fileTransport.getTag());
            downloadMap.put("FILE", executeFile);
            downloadMap.put("FILE_LENGTH", fileSize);
            downloadMap.put("FILE_CHANNEL", FileChannel.open(executeFile.toPath(), StandardOpenOption.READ));
            userDto.getDownloadFileMap().put(fileTransport.getTag(), downloadMap);

            // 向当前用户发送文件校验结果
            String message = "[ " + fileMessageFrame.getFileTransport().getFileName() + " ] 校验完成, 开始进行文件传输";
            Map<String, Object> sendMap = createDownloadFileSuccessMap(message, loopCount, fileSize);
            FileWriteEventParseUtil.parseMessageAndSend(socketChannelContext.getTransportProtocol().getSocketChannel(), sendMap, socketChannelContext);
            log.info("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] FileTransportManager | --> 待下载文件 = [{}] 校验完成，允许客户端 = [{}] 发送文件数据, thread = {}",
                fileTransport.getFileName(), fileTransport.getLaunchUserName(), Thread.currentThread().getName());
        } catch (IOException e) {
            log.error("[" + LocalTime.formatDate(LocalDateTime.now()) + "] FileTransportManager | --> 文件下载帧处理 error, path = {}, error = {}", executeFile.toPath().toAbsolutePath(), e.getMessage());
            e.printStackTrace();
            return Boolean.FALSE;
        }

        return Boolean.TRUE;
    }

    /**
     * 创建文件执行下载时文件校验返回错误数据集合Map
     * @param message
     */
    private static Map createDownloadFileErrorMap(String message, String operate) {
        Map<String, Object> map = new HashMap<>(8);
        map.put("code", 200);
        map.put("FILE_FRAME", BasicConstant.FILE_FRAME);
        map.put("frameType", FileMessageFrame.FrameType.DOWNLOAD.getBit());
        map.put("message", message);
        map.put("operate", operate);
        map.put("status", "1");
        map.put("time", Timestamp.valueOf(LocalDateTime.now()));
        return map;
    }

    /**
     * 创建待下载文件校验成功响应Map
     * @param message
     * @param loopCount
     * @param fileSumSize
     * @return
     */
    private static Map createDownloadFileSuccessMap(String message, Long loopCount, Long fileSumSize) {
        Map<String, Object> map = new HashMap<>(8);
        map.put("code", 200);
        map.put("FILE_FRAME", BasicConstant.FILE_FRAME);
        map.put("frameType", FileMessageFrame.FrameType.DOWNLOAD.getBit());
        map.put("message", message);
        map.put("operate", FileOperateEnum.DOWNLOAD_TRANSPORT_CONFIRM.getOperate());
        map.put("downloadLoopCount", loopCount);
        map.put("fileSize", fileSumSize);
        map.put("status", "1");
        map.put("time", Timestamp.valueOf(LocalDateTime.now()));
        return map;
    }

    /**
     * 发送消息
     * @param fileTransport  文件操作数据
     * @param socketChannelContext 待发送通道附件元素
     * @param bit 当前发送帧类型
     * @param fileOperateEnum 文件操作响应枚举
     * */
    private static void sendMessage(FileTransport fileTransport, SocketChannelContext socketChannelContext, String bit, FileOperateEnum fileOperateEnum) {
        Map<String, Object> map = new HashMap<>(8);
        map.put("code", 200);
        map.put("userName", fileTransport.getReceiveUserName());
        map.put("frameType", bit);
        map.put("message", fileOperateEnum.getMessage());
        map.put("messageType", fileOperateEnum.getType());
        map.put("operate", fileOperateEnum.getOperate());
        map.put("time", BasicConstant.SDF.format(new Date()));

        log.info("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] FileOnlineTransportSpecificHandler | --> file online transport send begin receive message success, 发起用户 = {}, thread = {}",
            fileTransport.getLaunchUserName(), Thread.currentThread().getName());
        WriteEventHandler.addSendData(map, socketChannelContext);
    }
}
