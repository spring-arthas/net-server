package com.alibaba.server.nio.service.chat.handler;

import com.alibaba.fastjson.JSON;
import com.alibaba.server.common.BasicConstant;
import com.alibaba.server.common.YesOrNoEnum;
import com.alibaba.server.nio.core.server.BasicServer;
import com.alibaba.server.nio.core.server.NioServerContext;
import com.alibaba.server.nio.handler.event.concret.WriteEventHandler;
import com.alibaba.server.nio.handler.pipe.ChannelContext;
import com.alibaba.server.nio.model.SocketChannelContext;
import com.alibaba.server.nio.model.TransportProtocol;
import com.alibaba.server.nio.model.chat.ChatMessageFrame;
import com.alibaba.server.nio.model.chat.ChatMessageFrame.FrameType;
import com.alibaba.server.nio.reactor.SubReactor;
import com.alibaba.server.nio.repository.file.service.FileService;
import com.alibaba.server.nio.repository.file.service.param.FileQueryParam;
import com.alibaba.server.nio.repository.file.service.param.FileUpdateParam;
import com.alibaba.server.nio.repository.user.service.UserService;
import com.alibaba.server.nio.repository.user.service.dto.UserDTO;
import com.alibaba.server.nio.repository.user.service.param.UserCreateParam;
import com.alibaba.server.nio.repository.user.service.param.UserQueryParam;
import com.alibaba.server.nio.repository.user.service.param.UserUpdateParam;
import com.alibaba.server.nio.service.api.AbstractChannelHandler;
import com.alibaba.server.nio.service.chat.model.*;
import com.alibaba.server.nio.service.file.model.PersonalFileCommon;
import com.alibaba.server.nio.service.file.model.PersonalStoreCommon;
import com.alibaba.server.nio.service.file.model.PersonalStoreRefresh;
import com.alibaba.server.util.LocalTime;
import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.springframework.util.CollectionUtils;
import java.io.File;
import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.time.LocalDateTime;
import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;

/**
 * 聊天读事件真实数据处理器
 *
 * @author spring
 */

@Slf4j
@SuppressWarnings("all")
public class ChatRealDataHandler extends AbstractChannelHandler {

    @Override
    public void handler(Object o, ChannelContext channelContext) {
        Map<String, Object> map = (Map<String, Object>) o;
        SocketChannelContext socketChannelContext = (SocketChannelContext) map.get("SOCKET_CHANNEL_CONTEXT");
        SocketChannel socketChannel = socketChannelContext.getSocketChannel();
        List<Object> realList = socketChannelContext.getTransportProtocol().getRealList();
        if (CollectionUtils.isEmpty(realList)) {
            return;
        }

        for (Object obj : realList) {
            ChatMessageFrame chatMessageFrame = (ChatMessageFrame) obj;

            // 注册帧
            if (ChatMessageFrame.FrameType.REGISTER.getFrameType()
                    .equals(chatMessageFrame.getFrameType().getFrameType())) {
                this.registerHandler(socketChannel, chatMessageFrame, socketChannelContext);
                realList.remove(chatMessageFrame);
            }

            // 登陆帧
            if (ChatMessageFrame.FrameType.LONGIN.getFrameType()
                    .equals(chatMessageFrame.getFrameType().getFrameType())) {
                this.loginHandler(socketChannel, chatMessageFrame, socketChannelContext);
                realList.remove(chatMessageFrame);
            }

            // 登出帧
            if (ChatMessageFrame.FrameType.LOGOUT.getFrameType()
                    .equals(chatMessageFrame.getFrameType().getFrameType())) {
                this.logoutHandler(socketChannel, chatMessageFrame, socketChannelContext);
                realList.remove(chatMessageFrame);
            }

            // 文本帧
            if (ChatMessageFrame.FrameType.TEXT.getFrameType().equals(chatMessageFrame.getFrameType().getFrameType())) {
                this.textHandler(socketChannel, chatMessageFrame, socketChannelContext);
                realList.remove(chatMessageFrame);
            }

            // 刷新用户列表帧
            if (ChatMessageFrame.FrameType.REFRESH.getFrameType()
                    .equals(chatMessageFrame.getFrameType().getFrameType())) {
                this.refreshHandler(socketChannel, chatMessageFrame, socketChannelContext);
                realList.remove(chatMessageFrame);
            }

            // 搜索用户帧
            if (ChatMessageFrame.FrameType.QUERY_USER.getFrameType()
                    .equals(chatMessageFrame.getFrameType().getFrameType())) {
                this.queryUserHandler(socketChannel, chatMessageFrame, socketChannelContext);
                realList.remove(chatMessageFrame);
            }

            // 心跳帧
            if (ChatMessageFrame.FrameType.HEART.getFrameType()
                    .equals(chatMessageFrame.getFrameType().getFrameType())) {
                this.heartHandler(socketChannel, chatMessageFrame, socketChannelContext);
                realList.remove(chatMessageFrame);
            }

            // 个人网盘文件夹刷新帧(刷新子文件夹以及文件列表)
            if (ChatMessageFrame.FrameType.PERSONAL_STORE_FILE_REFRESH.getFrameType()
                    .equals(chatMessageFrame.getFrameType().getFrameType())) {
                this.personalStoreRefreshHandler(socketChannel, chatMessageFrame, socketChannelContext);
                realList.remove(chatMessageFrame);
            }

            // 个人网盘文件夹创建帧
            if (ChatMessageFrame.FrameType.PERSONAL_STORE_FILE_CREATE.getFrameType()
                    .equals(chatMessageFrame.getFrameType().getFrameType())) {
                this.personalStoreCreateHandler(socketChannel, chatMessageFrame, socketChannelContext);
                realList.remove(chatMessageFrame);
            }

            // 个人网盘文件夹修改帧
            if (ChatMessageFrame.FrameType.PERSONAL_STORE_FILE_UPDATE.getFrameType()
                    .equals(chatMessageFrame.getFrameType().getFrameType())) {
                this.personalStoreUpdateHandler(socketChannel, chatMessageFrame, socketChannelContext);
                realList.remove(chatMessageFrame);
            }

            // 个人网盘文件夹删除帧

            // 个人网盘文件下载帧

            // 个人网盘文件删除帧
            if (ChatMessageFrame.FrameType.PERSONAL_FILE_DELETE.getFrameType()
                    .equals(chatMessageFrame.getFrameType().getFrameType())) {
                this.personalFileDeleteHandler(socketChannel, chatMessageFrame, socketChannelContext);
                realList.remove(chatMessageFrame);
            }
        }
    }

    /**
     * 注册处理,注册成功后由客户端直接发起登录,注册只是短链接
     * 
     * @param socketChannel
     * @param chatMessageFrame
     * @return
     */
    private void registerHandler(SocketChannel socketChannel, ChatMessageFrame chatMessageFrame, Object o) {
        ChatMessageRegister chatMessageRegister = JSON.parseObject(chatMessageFrame.getData(),
                ChatMessageRegister.class);
        // 1、判断当前用户是否已注册，未注册直接注册，已注册告知直接登录
        UserService userService = (UserService) BasicServer.classPathXmlApplicationContext.getBean(UserService.class);
        UserQueryParam userQueryParam = new UserQueryParam();
        userQueryParam.setUserName(chatMessageRegister.getUserName());
        UserDTO userDto = userService.getUserByName(userQueryParam);
        if (Optional.ofNullable(userDto).isPresent()) {
            this.sendRepeatRegisterMessage(socketChannel, chatMessageRegister, o);
            return;
        }

        // 2、创建待注册用户
        UserCreateParam userCreateParam = new UserCreateParam();
        userCreateParam.setUserName(chatMessageRegister.getUserName());
        userCreateParam.setPassword(chatMessageRegister.getPassword());
        userCreateParam.setPhone(chatMessageRegister.getPhone());
        userCreateParam.setMail(chatMessageRegister.getMail());
        userCreateParam.setRegister(YesOrNoEnum.Y.name());
        userCreateParam.setRegisterDate(new Date());
        userCreateParam.setLastLoginDate(null);
        userCreateParam.setStatus(BasicConstant.NOT_LOGIN);
        userDto = userService.create(userCreateParam);
        if (Optional.ofNullable(userDto).isPresent()) {
            this.sendSuccessRegisterMessage(socketChannel, chatMessageRegister, o);
        } else {
            this.sendFailRegisterMessage(socketChannel, chatMessageRegister, o);
        }
    }

    /**
     * 登录处理
     * 
     * @param socketChannel
     * @param chatMessageFrame
     * @return
     */
    private void loginHandler(SocketChannel socketChannel, ChatMessageFrame chatMessageFrame, Object o) {
        ChatMessageLogin chatMessageLogin = JSON.parseObject(chatMessageFrame.getData(), ChatMessageLogin.class);
        Map userMap = (Map) BasicServer.getMap().get(BasicConstant.USER);
        String remoteAddress = NioServerContext.getRemoteAddress(socketChannel);

        // 1、查询数据库判断当前用户是否存在
        UserService userService = (UserService) BasicServer.classPathXmlApplicationContext.getBean(UserService.class);
        UserQueryParam userQueryParam = new UserQueryParam();
        userQueryParam.setUserName(chatMessageLogin.getUserName());
        UserDTO userDto = userService.getUserByName(userQueryParam);
        if (Optional.ofNullable(userDto).isPresent()) {

            // 2、判断用户名或密码是否错误
            if (!StringUtils.equals(chatMessageLogin.getPassword(), userDto.getPassword())) {
                this.sendFailLoginMessage(socketChannel, chatMessageLogin, o, "密码错误, 登陆失败");
                return;
            }

            // 3、 判断用户是否已登录，内存判断，后期可更改为redis
            if (!CollectionUtils.isEmpty(userMap)) {
                Map<String, List<UserDTO>> onlineUserMap = (new ArrayList<UserDTO>(userMap.values())).stream()
                        .collect(Collectors.groupingBy(UserDTO::getUserName));
                if (onlineUserMap.containsKey(chatMessageLogin.getUserName())) {
                    // 用户已登录，发送重复登录消息
                    this.sendRepeatLoginMessage(socketChannel, chatMessageLogin, o);
                    return;
                }
            }

            // 3.1、未登录，db更新用户登录状态
            Date loginSuccessDate = new Date();
            UserUpdateParam userUpdateParam = new UserUpdateParam();
            userUpdateParam.setId(userDto.getId());
            userUpdateParam.setLastLoginDate(loginSuccessDate);
            userUpdateParam.setStatus("1");
            userService.update(userUpdateParam);

            // 3.2、内存更新用户登录状态
            userDto.setStatus(userUpdateParam.getStatus());
            userDto.setLastLoginDate(userUpdateParam.getLastLoginDate());
            userDto.setChatSocketChannel(socketChannel);
            userMap.put(remoteAddress, userDto);

            // 3.3、发送成功登录消息
            this.sendSuccessLoginMessage(socketChannel, chatMessageLogin, o, loginSuccessDate);
            return;
        }

        // 用户不存在,发送登录失败消息
        this.sendFailLoginMessage(socketChannel, chatMessageLogin, o, "用户不存在, 登陆失败");
    }

    /**
     * 登出处理
     * 
     * @param socketChannel
     * @param chatMessageFrame
     * @return
     */
    private void logoutHandler(SocketChannel socketChannel, ChatMessageFrame chatMessageFrame, Object o) {
        ChatMessageLogout chatMessageLogout = JSON.parseObject(chatMessageFrame.getData(), ChatMessageLogout.class);
        // 发送成功下线消息
        this.sendSuccessLogoutMessage(socketChannel, chatMessageLogout, o);
    }

    /**
     * 文本处理
     * 
     * @param socketChannel
     * @param chatMessageFrame
     * @return
     */
    private void textHandler(SocketChannel socketChannel, ChatMessageFrame chatMessageFrame, Object o) {
        ChatMessageText chatMessageText = JSON.parseObject(chatMessageFrame.getData(), ChatMessageText.class);
        Map userMap = (Map) BasicServer.getMap().get(BasicConstant.USER);
        Map<String, List<UserDTO>> onlineUserMap = (new ArrayList<UserDTO>(userMap.values())).stream()
                .collect(Collectors.groupingBy(UserDTO::getUserName));
        // 判断远程用户是否存在，存在则获取其socketChannel并发送数据
        if (onlineUserMap.containsKey(chatMessageText.getRemoteUserName())) {
            // 获取对方用户
            UserDTO userDto = ((UserDTO) (((List<UserDTO>) onlineUserMap.get(chatMessageText.getRemoteUserName()))
                    .get(0)));
            // 获取对方用户的Subreactor线程
            Map<String, Object> subReactorMap = (Map) BasicServer.getMap().get(BasicConstant.GLOBAL_MAIN_REACTOR);
            SubReactor subReactor = (SubReactor) ((Map) subReactorMap
                    .get(NioServerContext.getRemoteAddress(userDto.getChatSocketChannel()))).get("RUNNABLE");
            this.sendChatSuccessMessage(userDto.getChatSocketChannel(), chatMessageText,
                    subReactor.getSocketChannelContext());
            return;
        } else {
            this.sendChatFailMessage(socketChannel, chatMessageText, o);
        }
    }

    /**
     * 刷新在线用户
     * 
     * @param socketChannel
     * @param chatMessageFrame
     * @return
     */
    private void refreshHandler(SocketChannel socketChannel, ChatMessageFrame chatMessageFrame, Object o) {
        ChatMessageRefresh chatMessageRefresh = JSON.parseObject(chatMessageFrame.getData(), ChatMessageRefresh.class);
        Map userMap = (Map) BasicServer.getMap().get(BasicConstant.USER);

        // 获取所有在线用户(从数据库读取)
        List<UserDTO> onlineUser = (new ArrayList<UserDTO>(userMap.values())).stream()
                .filter(predict -> (predict.getStatus().equals("1"))
                        && (!StringUtils.equals(chatMessageRefresh.getUserName(), predict.getUserName())))
                .collect(Collectors.toList());

        // 构造发送数据
        Map<String, Object> refreshMap = new HashMap<>(8);
        refreshMap.put("code", 200);
        refreshMap.put("frameType", ChatMessageFrame.FrameType.REFRESH.getBit());
        refreshMap.put("data", onlineUser);

        // 发送数据
        WriteEventHandler.addSendData(refreshMap, o);
    }

    /**
     * 搜索用户帧
     * 
     * @param socketChannel
     * @param chatMessageFrame
     * @param socketChannelContext
     */
    private void queryUserHandler(SocketChannel socketChannel, ChatMessageFrame chatMessageFrame, Object o) {
        ChatMessageQuery chatMessageQuery = JSON.parseObject(chatMessageFrame.getData(), ChatMessageQuery.class);
        Map<String, UserDTO> userMap = (Map) BasicServer.getMap().get(BasicConstant.USER);

        List<UserDTO> queryResult = Lists.newArrayList();
        Iterator iterator = userMap.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry<String, UserDTO> entry = (Entry<String, UserDTO>) iterator.next();
            UserDTO userDto = entry.getValue();

            if (null != userDto) {
                String usernName = userDto.getUserName();
                if (usernName.startsWith(chatMessageQuery.getQueryUser())
                        || usernName.endsWith(chatMessageQuery.getQueryUser())
                        || usernName.contains(chatMessageQuery.getQueryUser())) {

                    queryResult.add(userDto);
                }
            }
        }

        Map<String, Object> queryUserMap = new HashMap<>(8);
        queryUserMap.put("code", 200);
        queryUserMap.put("frameType", ChatMessageFrame.FrameType.QUERY_USER.getBit());
        queryUserMap.put("data", queryResult);
        WriteEventHandler.addSendData(queryUserMap, o);
    }

    /**
     * 心跳帧
     * 
     * @param socketChannel
     * @param chatMessageFrame
     * @param o
     * @throws IOException
     */
    private void heartHandler(SocketChannel socketChannel, ChatMessageFrame chatMessageFrame, Object o) {
        ChatMessageHeart chatMessageHeart = JSON.parseObject(chatMessageFrame.getData(), ChatMessageHeart.class);
        // 打印客户端心跳帧
        log.info("[ " + LocalTime.formatDate(LocalDateTime.now())
                + " ] ChatRealDataHandler | --> receive client heart frame, userName = {}, interval = {}, data = {}, address = {}, thread = {}",
                chatMessageHeart.getUserName(), chatMessageHeart.getHeartInterval(), chatMessageHeart.getData(),
                NioServerContext.getRemoteAddress(socketChannel), Thread.currentThread().getName());

        // 发送心跳响应帧
        Map<String, Object> map = new HashMap<>(8);
        map.put("code", 200);
        map.put("userName", chatMessageHeart.getUserName());
        map.put("frameType", ChatMessageFrame.FrameType.HEART.getBit());
        map.put("message", "HEART_RESPONSE");
        map.put("time", BasicConstant.SDF.format(new Date()));
        WriteEventHandler.addSendData(map, o);
    }

    /**
     * 个人网盘文件夹刷新帧
     * 
     * @param socketChannel
     * @param chatMessageFrame
     * @param o
     * @throws IOException
     */
    private void personalStoreRefreshHandler(SocketChannel socketChannel, ChatMessageFrame chatMessageFrame, Object o) {
        PersonalStoreRefresh personalStoreRefresh = JSON.parseObject(chatMessageFrame.getData(),
                PersonalStoreRefresh.class);
        log.info("[ " + LocalTime.formatDate(LocalDateTime.now())
                + " ] ChatRealDataHandler | --> receive client personal folder store refresh frame, address = {}, thread = {}",
                NioServerContext.getRemoteAddress(socketChannel), Thread.currentThread().getName());

        // 获取个人网盘文件夹信息
        // 判断操作系统类型， 获取个人文件路径
        String completeFilePath = "", relativeFilePath = "";
        if (BasicServer.getMap().get(BasicConstant.OS_NAME).toString().contains("Win")) { // win
            if (!personalStoreRefresh.getFilePath().endsWith(File.separator)) {
                completeFilePath = BasicServer.getMap().get(BasicConstant.NIO_FILE_BASE_PATH_WINDOWS).toString()
                        + personalStoreRefresh.getFilePath() + File.separator;
                relativeFilePath = personalStoreRefresh.getFilePath() + File.separator;
            } else {
                completeFilePath = BasicServer.getMap().get(BasicConstant.NIO_FILE_BASE_PATH_WINDOWS).toString()
                        + personalStoreRefresh.getFilePath();
                relativeFilePath = personalStoreRefresh.getFilePath();
            }
        } else { // linux or Mac
            if (!personalStoreRefresh.getFilePath().endsWith(File.separator)) {
                completeFilePath = BasicServer.getMap().get(BasicConstant.NIO_FILE_BASE_PATH_LINUX_MAC).toString()
                        + personalStoreRefresh.getFilePath() + File.separator;
                relativeFilePath = personalStoreRefresh.getFilePath() + File.separator;
            } else {
                completeFilePath = BasicServer.getMap().get(BasicConstant.NIO_FILE_BASE_PATH_LINUX_MAC).toString()
                        + personalStoreRefresh.getFilePath();
                relativeFilePath = personalStoreRefresh.getFilePath();
            }
        }

        // 查询文件夹信息
        FileService fileService = BasicServer.classPathXmlApplicationContext.getBean(FileService.class);
        FileQueryParam fileQueryParam = new FileQueryParam();
        fileQueryParam.setPId(
                StringUtils.isBlank(personalStoreRefresh.getPId()) ? -1 : Long.valueOf(personalStoreRefresh.getPId()));
        fileQueryParam.setFileName(personalStoreRefresh.getFileName());
        fileQueryParam.setUserName(personalStoreRefresh.getUserName());
        fileQueryParam.setFileSize(personalStoreRefresh.getFileSize());
        fileQueryParam.setFileType("NOT_FILE");
        fileQueryParam.setIsFile(YesOrNoEnum.N.name());
        fileQueryParam.setIsExist(YesOrNoEnum.Y.name());
        fileQueryParam.setCurrentPage(personalStoreRefresh.getCurrentPage());
        fileQueryParam.setPageSize(personalStoreRefresh.getPageSize());
        Map<String, Object> map = new HashMap<>(8);
        map.put("code", 200);
        map.put("userName", personalStoreRefresh.getUserName());
        map.put("frameType", ChatMessageFrame.FrameType.PERSONAL_STORE_FILE_REFRESH.getBit());
        map.put("data", fileService.getFolderFileTree(fileQueryParam, completeFilePath, relativeFilePath));
        map.put("time", BasicConstant.SDF.format(new Date()));
        WriteEventHandler.addSendData(map, o);
    }

    /**
     * 个人网盘文件夹创建帧
     * 
     * @param socketChannel
     * @param chatMessageFrame
     * @param o
     * @throws IOException
     */
    private void personalStoreCreateHandler(SocketChannel socketChannel, ChatMessageFrame chatMessageFrame, Object o) {
        PersonalStoreCommon personalStoreCommon = JSON.parseObject(chatMessageFrame.getData(),
                PersonalStoreCommon.class);
        log.info("[ " + LocalTime.formatDate(LocalDateTime.now())
                + " ] ChatRealDataHandler | --> receive client personal folder store create Frame, userName = {}, address = {}, thread = {}",
                personalStoreCommon.getUserName(), NioServerContext.getRemoteAddress(socketChannel),
                Thread.currentThread().getName());

        // 获取个人网盘文件夹信息
        // 判断操作系统类型， 获取个人文件路径
        String completeFilePath = "", relativeFilePath = "";
        if (BasicServer.getMap().get(BasicConstant.OS_NAME).toString().contains("Win")) { // win
            completeFilePath = BasicServer.getMap().get(BasicConstant.NIO_FILE_BASE_PATH_WINDOWS).toString()
                    + personalStoreCommon.getFilePath() + BasicConstant.FILE_WINDOWS_SEPARATOR;
            relativeFilePath = personalStoreCommon.getFilePath() + BasicConstant.FILE_WINDOWS_SEPARATOR;

            if (!personalStoreCommon.getNewFileName().equals("")) {
                completeFilePath += personalStoreCommon.getNewFileName() + BasicConstant.FILE_WINDOWS_SEPARATOR;
                relativeFilePath += personalStoreCommon.getNewFileName() + BasicConstant.FILE_WINDOWS_SEPARATOR;
            }
        } else { // linux or Mac
            completeFilePath = BasicServer.getMap().get(BasicConstant.NIO_FILE_BASE_PATH_LINUX_MAC).toString()
                    + personalStoreCommon.getFilePath() + BasicConstant.FILE_LINUX_MAC_SEPARATOR;
            relativeFilePath = personalStoreCommon.getFilePath() + BasicConstant.FILE_LINUX_MAC_SEPARATOR;
            if (!personalStoreCommon.getNewFileName().equals("")) {
                completeFilePath += personalStoreCommon.getNewFileName() + BasicConstant.FILE_LINUX_MAC_SEPARATOR;
                relativeFilePath += personalStoreCommon.getNewFileName() + BasicConstant.FILE_LINUX_MAC_SEPARATOR;
            }
        }

        // 创建文件信息
        FileService fileService = BasicServer.classPathXmlApplicationContext.getBean(FileService.class);
        FileQueryParam fileQueryParam = new FileQueryParam();
        fileQueryParam.setPId(personalStoreCommon.getId());
        fileQueryParam.setFileName(personalStoreCommon.getNewFileName());
        fileQueryParam.setUserName(personalStoreCommon.getUserName());
        fileQueryParam.setFilePath(relativeFilePath);
        fileQueryParam.setFileSize(personalStoreCommon.getFileSize());
        fileQueryParam.setFileType("NOT_FILE");
        fileQueryParam.setIsFile(YesOrNoEnum.N.name());
        fileQueryParam.setIsExist(YesOrNoEnum.Y.name());
        Map<String, Object> map = new HashMap<>(8);
        map.put("code", 200);
        map.put("userName", personalStoreCommon.getUserName());
        map.put("frameType", ChatMessageFrame.FrameType.PERSONAL_STORE_FILE_CREATE.getBit());
        map.put("data", fileService.create(fileQueryParam, completeFilePath));
        map.put("time", BasicConstant.SDF.format(new Date()));
        WriteEventHandler.addSendData(map, o);
    }

    /**
     * 个人网盘文件夹修改帧
     * 
     * @param socketChannel
     * @param chatMessageFrame
     * @param o
     * @throws IOException
     */
    private void personalStoreUpdateHandler(SocketChannel socketChannel, ChatMessageFrame chatMessageFrame, Object o) {
        PersonalStoreCommon personalStoreCommon = JSON.parseObject(chatMessageFrame.getData(),
                PersonalStoreCommon.class);
        log.info("[ " + LocalTime.formatDate(LocalDateTime.now())
                + " ] ChatRealDataHandler | --> receive client personal folder store update Frame, userName = {}, address = {}, thread = {}",
                personalStoreCommon.getUserName(), NioServerContext.getRemoteAddress(socketChannel),
                Thread.currentThread().getName());

        // 获取个人网盘文件夹信息
        // 判断操作系统类型， 获取个人文件路径
        String completeFilePath = "", orginFilePath = "", newRelativeFilePath = "";
        if (BasicServer.getMap().get(BasicConstant.OS_NAME).toString().contains("Win")) { // win
            completeFilePath = BasicServer.getMap().get(BasicConstant.NIO_FILE_BASE_PATH_WINDOWS).toString()
                    + personalStoreCommon.getFilePath() + BasicConstant.FILE_WINDOWS_SEPARATOR;

            // 构建原始文件夹全路径
            orginFilePath = completeFilePath;

            // 构建新文件夹相对路径
            int relativePostion = personalStoreCommon.getFilePath()
                    .lastIndexOf(personalStoreCommon.getOriginFileName());
            newRelativeFilePath = personalStoreCommon.getFilePath().substring(0, relativePostion);
            newRelativeFilePath += personalStoreCommon.getNewFileName() + BasicConstant.FILE_WINDOWS_SEPARATOR;

            // 构建新文件夹全路径
            int position = completeFilePath.lastIndexOf(personalStoreCommon.getOriginFileName());
            completeFilePath = completeFilePath.substring(0, position);
            completeFilePath += personalStoreCommon.getNewFileName() + BasicConstant.FILE_WINDOWS_SEPARATOR;
        } else { // linux or Mac
            completeFilePath = BasicServer.getMap().get(BasicConstant.NIO_FILE_BASE_PATH_LINUX_MAC).toString()
                    + personalStoreCommon.getFilePath() + BasicConstant.FILE_LINUX_MAC_SEPARATOR;

            // 构建原始文件夹全路径
            orginFilePath = completeFilePath;

            // 构建新文件夹相对路径
            int relativePostion = personalStoreCommon.getFilePath()
                    .lastIndexOf(personalStoreCommon.getOriginFileName());
            newRelativeFilePath = personalStoreCommon.getFilePath().substring(0, relativePostion);
            newRelativeFilePath += personalStoreCommon.getNewFileName() + BasicConstant.FILE_LINUX_MAC_SEPARATOR;

            // 构建新文件夹全路径
            int position = completeFilePath.lastIndexOf(personalStoreCommon.getOriginFileName());
            completeFilePath = completeFilePath.substring(0, position);
            completeFilePath += personalStoreCommon.getNewFileName() + BasicConstant.FILE_LINUX_MAC_SEPARATOR;
        }

        // 修改文件信息
        FileService fileService = BasicServer.classPathXmlApplicationContext.getBean(FileService.class);
        FileUpdateParam fileUpdateParam = new FileUpdateParam();
        fileUpdateParam.setId(personalStoreCommon.getId());
        fileUpdateParam.setFileName(personalStoreCommon.getNewFileName());
        fileUpdateParam.setFilePath(newRelativeFilePath);
        Map<String, Object> map = new HashMap<>(8);
        map.put("code", 200);
        map.put("userName", personalStoreCommon.getUserName());
        map.put("frameType", ChatMessageFrame.FrameType.PERSONAL_STORE_FILE_UPDATE.getBit());
        map.put("data", fileService.update(fileUpdateParam, orginFilePath, completeFilePath));
        map.put("time", BasicConstant.SDF.format(new Date()));
        WriteEventHandler.addSendData(map, o);
    }

    /**
     * 个人网盘文件删除帧
     * 
     * @param socketChannel
     * @param chatMessageFrame
     * @param o
     * @throws IOException
     */
    private void personalFileDeleteHandler(SocketChannel socketChannel, ChatMessageFrame chatMessageFrame, Object o) {
        PersonalFileCommon personalFileCommon = JSON.parseObject(chatMessageFrame.getData(), PersonalFileCommon.class);
        log.info("[ " + LocalTime.formatDate(LocalDateTime.now())
                + " ] ChatRealDataHandler | --> receive client personal file delete Frame, userName = {}, address = {}, thread = {}",
                personalFileCommon.getUserName(), NioServerContext.getRemoteAddress(socketChannel),
                Thread.currentThread().getName());

        // 1、判断操作系统类型， 获取个人文件路径
        String osName = BasicServer.getMap().get(BasicConstant.OS_NAME).toString();
        String rootFilePath = osName.contains("Win")
                ? BasicServer.getMap().get(BasicConstant.NIO_FILE_BASE_PATH_WINDOWS).toString()
                : BasicServer.getMap().get(BasicConstant.NIO_FILE_BASE_PATH_LINUX_MAC).toString();

        // 2、解析文件操作
        List<Long> fileIdList = Lists.newArrayList();
        // String[] fileNameArray =
        // personalFileCommon.getFileNames().contains(",")?personalFileCommon.getFileNames().split(","):new
        // String[] {personalFileCommon.getFileNames()};
        String[] filePathArray = personalFileCommon.getFilePaths().contains(",")
                ? personalFileCommon.getFilePaths().split(",")
                : new String[] { personalFileCommon.getFilePaths() };
        String[] fileTagArray = personalFileCommon.getTags().contains(",") ? personalFileCommon.getTags().split(",")
                : new String[] { personalFileCommon.getTags() };
        for (int i = 0; i < filePathArray.length; i++) {
            String currentFilePath = rootFilePath + filePathArray[i];

            // 判断当前文件是否存在，存在则删除，不考虑文件不存在的情况
            File file = new File(currentFilePath);
            if (file.exists()) {
                if (file.delete()) {
                    // 文件删除成功
                    fileIdList.add(Long.valueOf(fileTagArray[i]));
                }

                // 文件存在，且未删除成功，则不能更新DB del状态，此处不进行文件id追加
            } else {
                // 文件不存在，但是表格却显示出已删除的文件信息，那只记录当前文件id，用于更新数据库，并响应客户端从表格中删除已经不存在的文件对应的行记录
                fileIdList.add(Long.valueOf(fileTagArray[i]));
            }
        }

        // 执行文件删除状态批量更新
        FileService fileService = BasicServer.classPathXmlApplicationContext.getBean(FileService.class);
        FileQueryParam fileQueryParam = new FileQueryParam();
        fileQueryParam.setId(personalFileCommon.getFolderId());
        fileQueryParam.setIsFile(YesOrNoEnum.N.name());
        fileQueryParam.setIsExist(YesOrNoEnum.Y.name());
        fileService.deleteFile(fileQueryParam, fileIdList);

        Map<String, Object> map = new HashMap<>(8);
        map.put("code", 200);
        map.put("FILE_FRAME", "FILE_FRAME");
        map.put("userName", personalFileCommon.getUserName());
        map.put("frameType", ChatMessageFrame.FrameType.PERSONAL_FILE_DELETE.getBit());
        map.put("data", fileIdList);
        map.put("time", BasicConstant.SDF.format(new Date()));
        WriteEventHandler.addSendData(map, o);
    }

    /**
     * 发送成功注册消息
     * 
     * @param socketChannel
     * @param chatMessageRegister
     * @param o
     * @return
     */
    private void sendSuccessRegisterMessage(SocketChannel socketChannel, ChatMessageRegister chatMessageRegister,
            Object o) {
        Map<String, Object> map = new HashMap<>(8);
        map.put("code", 200);
        map.put("userName", chatMessageRegister.getUserName());
        map.put("frameType", ChatMessageFrame.FrameType.REGISTER.getBit());
        map.put("message", "注册成功");
        map.put("time", BasicConstant.SDF.format(new Date()));

        log.info(
                "[ " + LocalTime.formatDate(LocalDateTime.now())
                        + " ] ChatRealDataHandler | --> register success, userName = {}, thread = {}",
                chatMessageRegister.getUserName(), Thread.currentThread().getName());
        WriteEventHandler.addSendData(map, o);
        // CoreServerContext.sendMessage(socketChannel,
        // BasicConstant.NIO_SERVER_MAIN_CORE_CHAT_SELECTOR_THREAD, o);
    }

    /**
     * 发送重复注册消息
     * 
     * @param socketChannel
     * @param chatMessageRegister
     * @param o
     * @return
     */
    private void sendRepeatRegisterMessage(SocketChannel socketChannel, ChatMessageRegister chatMessageRegister,
            Object o) {
        Map<String, Object> map = new HashMap<>(8);
        map.put("code", 200);
        map.put("userName", chatMessageRegister.getUserName());
        map.put("frameType", ChatMessageFrame.FrameType.REGISTER.getBit());
        map.put("messageType", "REPEAT");
        map.put("message", "[ " + chatMessageRegister.getUserName() + "] 已存在, 禁止重复注册");
        map.put("time", BasicConstant.SDF.format(new Date()));

        log.info(
                "[ " + LocalTime.formatDate(LocalDateTime.now())
                        + " ] ChatRealDataHandler | --> register repeated, userName = {}, thread = {}",
                chatMessageRegister.getUserName(), Thread.currentThread().getName());
        WriteEventHandler.addSendData(map, o);
    }

    /**
     * 发送注册失败消息
     * 
     * @param socketChannel
     * @param chatMessageLogout
     * @param o
     * @return
     */
    private void sendFailRegisterMessage(SocketChannel socketChannel, ChatMessageRegister chatMessageRegister,
            Object o) {
        Map<String, Object> map = new HashMap<>(8);
        map.put("code", 200);
        map.put("userName", chatMessageRegister.getUserName());
        map.put("frameType", ChatMessageFrame.FrameType.REGISTER.getBit());
        map.put("message", "注册失败");
        map.put("time", BasicConstant.SDF.format(new Date()));

        log.info(
                "[ " + LocalTime.formatDate(LocalDateTime.now())
                        + " ] ChatRealDataHandler | --> register failed, userName = {}, thread = {}",
                chatMessageRegister.getUserName(), Thread.currentThread().getName());
        WriteEventHandler.addSendData(map, o);
    }

    /**
     * 发送成功登录消息
     * 
     * @param socketChannel
     * @param chatMessageLogin
     * @param o
     * @return
     */
    private void sendSuccessLoginMessage(SocketChannel socketChannel, ChatMessageLogin chatMessageLogin, Object o,
            Date loginSuccessDate) {
        Map<String, Object> map = new HashMap<>(8);
        map.put("code", 200);
        map.put("userName", chatMessageLogin.getUserName());
        map.put("frameType", ChatMessageFrame.FrameType.LONGIN.getBit());
        map.put("message", "登录成功");
        map.put("time", BasicConstant.SDF.format(loginSuccessDate));

        log.info(
                "[ " + LocalTime.formatDate(LocalDateTime.now())
                        + " ] ChatRealDataHandler | --> login success, userName = {}, thread = {}",
                chatMessageLogin.getUserName(), Thread.currentThread().getName());
        WriteEventHandler.addSendData(map, o);
        // CoreServerContext.sendMessage(socketChannel,
        // BasicConstant.NIO_SERVER_MAIN_CORE_CHAT_SELECTOR_THREAD, o);
    }

    /**
     * 发送重复登录消息
     * 
     * @param socketChannel
     * @param chatMessageLogin
     * @param o
     * @return
     */
    private void sendRepeatLoginMessage(SocketChannel socketChannel, ChatMessageLogin chatMessageLogin, Object o) {
        Map<String, Object> map = new HashMap<>(8);
        map.put("code", 201);
        map.put("userName", chatMessageLogin.getUserName());
        map.put("frameType", ChatMessageFrame.FrameType.LONGIN.getBit());
        map.put("message", "禁止重复登录");
        map.put("time", BasicConstant.SDF.format(new Date()));

        log.info(
                "[ " + LocalTime.formatDate(LocalDateTime.now())
                        + " ] ChatRealDataHandler | --> repeat login, userName = {}, thread = {}",
                chatMessageLogin.getUserName(), Thread.currentThread().getName());
        WriteEventHandler.addSendData(map, o);
        // CoreServerContext.sendMessage(socketChannel,
        // BasicConstant.NIO_SERVER_MAIN_CORE_CHAT_SELECTOR_THREAD, o);
    }

    /**
     * 发送登录失败消息
     * 
     * @param socketChannel
     * @param chatMessageLogin
     * @param o
     * @return
     */
    private void sendFailLoginMessage(SocketChannel socketChannel, ChatMessageLogin chatMessageLogin, Object o,
            String message) {
        Map<String, Object> map = new HashMap<>(8);
        map.put("code", 202);
        map.put("userName", chatMessageLogin.getUserName());
        map.put("frameType", ChatMessageFrame.FrameType.LONGIN.getBit());
        map.put("message", message);
        map.put("time", BasicConstant.SDF.format(new Date()));

        log.info(
                "[ " + LocalTime.formatDate(LocalDateTime.now())
                        + " ] ChatRealDataHandler | --> fail login, userName = {}, thread = {}",
                chatMessageLogin.getUserName(), Thread.currentThread().getName());
        WriteEventHandler.addSendData(map, o);
        // CoreServerContext.sendMessage(socketChannel,
        // BasicConstant.NIO_SERVER_MAIN_CORE_CHAT_SELECTOR_THREAD, o);
    }

    /**
     * 发送登出失败消息
     * 
     * @param socketChannel
     * @param chatMessageLogout
     * @param o
     * @return
     */
    private void sendFailLogoutMessage(SocketChannel socketChannel, ChatMessageLogout chatMessageLogout, Object o) {
        Map<String, Object> map = new HashMap<>(8);
        map.put("code", 200);
        map.put("userName", chatMessageLogout.getUserName());
        map.put("frameType", ChatMessageFrame.FrameType.LOGOUT.getBit());
        map.put("message", "下线失败");
        map.put("time", BasicConstant.SDF.format(new Date()));

        log.info(
                "[ " + LocalTime.formatDate(LocalDateTime.now())
                        + " ] ChatRealDataHandler | --> logout failed, userName = {}, thread = {}",
                chatMessageLogout.getUserName(), Thread.currentThread().getName());
        WriteEventHandler.addSendData(map, o);
        // CoreServerContext.sendMessage(socketChannel,
        // BasicConstant.NIO_SERVER_MAIN_CORE_CHAT_SELECTOR_THREAD, o);
    }

    /**
     * 发送成功登出消息
     * 
     * @param socketChannel
     * @param chatMessageLogout
     * @param o
     * @return
     */
    private void sendSuccessLogoutMessage(SocketChannel socketChannel, ChatMessageLogout chatMessageLogout, Object o) {
        Map<String, Object> map = new HashMap<>(8);
        map.put("code", 200);
        map.put("userName", chatMessageLogout.getUserName());
        map.put("frameType", ChatMessageFrame.FrameType.LOGOUT.getBit());
        map.put("message", "下线成功");
        map.put("time", BasicConstant.SDF.format(new Date()));

        // 1、向客户端发送下线成功的消息
        WriteEventHandler.addSendData(map, o);

        // 2、下线操作，释放用户资源，但不关闭当前socketChannel连接，因为有可能以当前SocketChannel重新登录或是登录其他用户，只有关闭了客户端才关闭当前SocketChannel
        Boolean result = NioServerContext.handleSubReactor(NioServerContext.getRemoteAddress(socketChannel),
                Boolean.FALSE);
        log.info(
                "[ " + LocalTime.formatDate(LocalDateTime.now())
                        + " ] ChatRealDataHandler | --> [{}] {}, remoteAddress = {}, thread = {}",
                String.valueOf(map.get("userName")), (result.equals(Boolean.TRUE) ? "下线成功" : "下线失败"),
                NioServerContext.getRemoteAddress(socketChannel), Thread.currentThread().getName());
    }

    /**
     * 发送成功聊天消息
     * 
     * @param socketChannel
     * @param chatMessageText
     * @param o
     * @return
     */
    private void sendChatSuccessMessage(SocketChannel socketChannel, ChatMessageText chatMessageText, Object o) {
        Map<String, Object> map = new HashMap<>(8);
        map.put("code", 200);
        map.put("userName", chatMessageText.getCurrentUserName());
        map.put("frameType", ChatMessageFrame.FrameType.TEXT.getBit());
        map.put("message", chatMessageText.getContent());
        map.put("time", BasicConstant.SDF.format(new Date()));

        log.info("[ " + LocalTime.formatDate(LocalDateTime.now())
                + " ] ChatRealDataHandler | --> send remote user text, userName = {}, content = {}, thread = {}",
                chatMessageText.getCurrentUserName(), chatMessageText.getContent(), Thread.currentThread().getName());
        WriteEventHandler.addSendData(map, o);
        // CoreServerContext.sendMessage(socketChannel,
        // BasicConstant.NIO_SERVER_MAIN_CORE_CHAT_SELECTOR_THREAD, o);
    }

    /**
     * 发送聊天失败消息
     * 
     * @param socketChannel
     * @param chatMessageText
     * @param o
     * @return
     */
    private void sendChatFailMessage(SocketChannel socketChannel, ChatMessageText chatMessageText, Object o) {
        Map<String, Object> map = new HashMap<>(8);
        map.put("code", 200);
        map.put("userName", chatMessageText.getRemoteUserName());
        map.put("frameType", ChatMessageFrame.FrameType.TEXT.getBit());
        map.put("message", "用户已下线,请尝试刷新列表");
        map.put("time", BasicConstant.SDF.format(new Date()));

        log.info(
                "[ " + LocalTime.formatDate(LocalDateTime.now())
                        + " ] ChatRealDataHandler | --> has logout, userName = {}, thread = {}",
                chatMessageText.getCurrentUserName(), Thread.currentThread().getName());
        WriteEventHandler.addSendData(map, o);
        // CoreServerContext.sendMessage(socketChannel,
        // BasicConstant.NIO_SERVER_MAIN_CORE_CHAT_SELECTOR_THREAD, o);
    }
}
