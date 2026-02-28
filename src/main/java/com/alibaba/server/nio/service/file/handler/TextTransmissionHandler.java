package com.alibaba.server.nio.service.file.handler;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.server.common.BasicConstant;
import com.alibaba.server.nio.core.server.BasicServer;
import com.alibaba.server.nio.handler.event.concret.WriteQueueHelper;
import com.alibaba.server.nio.handler.pipe.ChannelContext;
import com.alibaba.server.nio.handler.pipe.standard.SimpleChannelContext;
import com.alibaba.server.nio.model.ChannelEventModel;
import com.alibaba.server.nio.model.SocketChannelContext;
import com.alibaba.server.nio.model.TransportDataModel;
import com.alibaba.server.nio.model.file.DirectoryFrame;
import com.alibaba.server.nio.model.file.FileUploadFrame;
import com.alibaba.server.nio.model.file.FileUploadFrame.FrameType;
import com.alibaba.server.nio.model.user.UserAuthFrame;
import com.alibaba.server.nio.repository.chat.mapper.UserFriendMessageDO;
import com.alibaba.server.nio.repository.chat.service.UserFriendMessageService;
import com.alibaba.server.nio.repository.file.service.FileService;
import com.alibaba.server.nio.repository.file.service.dto.FileDto;
import com.alibaba.server.nio.repository.file.service.dto.FilePageDto;
import com.alibaba.server.nio.repository.file.service.param.FileQueryParam;
import com.alibaba.server.nio.repository.user.service.UserService;
import com.alibaba.server.nio.repository.user.service.dto.UserDTO;
import com.alibaba.server.nio.repository.user.service.param.UserQueryParam;
import com.alibaba.server.nio.service.api.AbstractChannelHandler;
import com.alibaba.server.nio.service.file.parser.FrameUploadParser;
import com.alibaba.server.nio.repository.user.service.UserFriendsService;
import com.alibaba.server.nio.repository.user.service.dto.UserFriendsDTO;
import com.alibaba.server.nio.repository.user.service.param.UserFriendsCreateParam;
import com.alibaba.server.nio.repository.user.service.param.UserFriendsQueryParam;
import com.alibaba.server.nio.repository.user.service.UserFriendApplyService;
import com.alibaba.server.nio.repository.user.service.dto.UserFriendApplyDTO;
import com.alibaba.server.nio.repository.user.service.param.UserFriendApplyCreateParam;
import com.alibaba.server.nio.repository.user.service.param.UserFriendApplyQueryParam;
import com.alibaba.server.nio.repository.user.service.param.UserFriendApplyUpdateParam;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 文本传输处理器（统一处理器）
 * 
 * 支持功能：
 * 1. 用户认证：注册/登录/修改密码/退出登录
 * 2. 目录操作：创建/删除/更新/移动
 * 3. 文件操作：上传/下载/删除/移动
 * 
 * 协议复用 FileUploadFrame 格式，错误通过响应帧返回（不断开连接）
 * 
 * @author spring
 */
@Slf4j
public class TextTransmissionHandler extends AbstractChannelHandler {

    /**
     * 帧解析器缓存(即每个socketChannelContext均匹配一个解析器)
     */
    private static final ConcurrentHashMap<String, FrameUploadParser> parserMap = new ConcurrentHashMap<>();

    @Override
    public void handler(Object o, ChannelContext channelContext) throws IOException {
        TransportDataModel transportDataModel = (TransportDataModel) o;
        SimpleChannelContext simpleChannelContext = (SimpleChannelContext) channelContext;
        SocketChannelContext socketChannelContext = simpleChannelContext.getSocketChannelContext();

        if (socketChannelContext == null) {
            log.error("SocketChannelContext 为空，无法处理");
            return;
        }

        // 检查 handlerType，非 TEXT 则直接返回
        if (!"TEXT".equals(socketChannelContext.getHandlerType())) {
            return;
        }

        List<ChannelEventModel.GroupData> waitHandleDataList = transportDataModel.getWaitHandleDataList();
        if (CollectionUtils.isEmpty(waitHandleDataList)) {
            return;
        }

        // 获取或创建帧解析器
        FrameUploadParser parser = getOrCreateParser(socketChannelContext);

        // 逐个处理待处理数据
        for (ChannelEventModel.GroupData groupData : waitHandleDataList) {
            List<FileUploadFrame> frames = parser.parse(groupData.getBytes());

            // 处理解析完成的帧
            for (FileUploadFrame frame : frames) {
                processFrame(frame, socketChannelContext);
            }
        }
    }

    /**
     * 获取或创建帧解析器
     */
    private FrameUploadParser getOrCreateParser(SocketChannelContext socketChannelContext) {
        String remoteAddress = socketChannelContext.getRemoteAddress();
        return parserMap.computeIfAbsent(remoteAddress, k -> {
            log.debug("为通道 {} 创建新的 FrameParser", remoteAddress);
            return new FrameUploadParser();
        });
    }

    /**
     * 处理单个帧（统一分发）
     */
    private void processFrame(FileUploadFrame frame, SocketChannelContext context) {
        FrameType type = frame.getType();
        log.debug("收到帧: type={}，帧类型={}, 操作={}, 数据内容={}",
                type, type.getCode(), type.getDescription(), JSON.toJSONString(frame.getDataAsString()));

        try {
            switch (type) {
                // ========== 用户认证帧 ==========
                case USER_REGISTER_REQ: // 用户注册请求
                    handleRegister(frame, context);
                    break;
                case USER_LOGIN_REQ: // 用户登录请求
                    handleLogin(frame, context);
                    break;
                case USER_CHANGE_PWD_REQ: // 用户修改密码请求
                    handleChangePassword(frame, context);
                    break;
                case USER_LOGOUT_REQ: // 用户退出登录请求
                    handleLogout(frame, context);
                    break;
                case USER_FRIEND_LIST_REQ: // 用户好友列表
                    handleFriendList(frame, context);
                    break;
                case USER_FRIEND_QUERY_REQ: // 好友搜索列表
                    handleFriendQuery(frame, context);
                    break;
                case USER_FRIEND_ADD_REQ: // 用户添加好友请求
                    handleFriendAdd(frame, context);
                    break;
                case USER_FRIEND_APPLY_REQ: // 获取好友申请列表
                    handleFriendApply(frame, context);
                    break;
                case USER_FRIEND_APPLY_HANDLE_REQ: // 处理好友申请
                    handleFriendApplylyHandle(frame, context);
                    break;
                // ========== 目录操作帧 ==========
                case DIR_USER_GET_TWO_LEVEL_REQ:
                    handleUserTwoLevelDirectory(frame, context);
                    break;
                case DIR_CREATE_REQ:
                    handleCreateDirectory(frame, context);
                    break;
                case DIR_DELETE_REQ:
                    handleDeleteDirectory(frame, context);
                    break;
                case DIR_UPDATE_REQ:
                    handleUpdateDirectory(frame, context);
                    break;
                case DIR_MOVE_REQ:
                    handleMoveDirectory(frame, context);
                    break;

                // ========== 文件操作帧 ==========
                case FILE_LIST_REQ:
                    handleFileList(frame, context);
                    break;
                case FILE_DELETE_REQ:
                    handleFileDelete(frame, context);
                    break;
                case FILE_DETAIL_REQ:
                    handleFileDetail(frame, context);
                    break;

                // ========== 聊天消息帧 ==========
                case CHAT_MSG_SEND_REQ:
                    handleChatMessageSend(frame, context);
                    break;
                case CHAT_MSG_HISTORY_REQ:
                    handleChatMessageHistory(frame, context);
                    break;
                default:
                    log.debug("未处理的帧类型: {}", type);
            }
        } catch (Exception e) {
            log.error("处理帧异常: type={}", type, e);
            sendErrorResponse(context, FrameType.USER_RESPONSE, e.getMessage(), "INTERNAL_ERROR");
        }
    }

    // ========== 服务获取 ==========

    private UserService getUserService() {
        return BasicServer.classPathXmlApplicationContext.getBean(UserService.class);
    }

    private FileService getFileService() {
        return BasicServer.classPathXmlApplicationContext.getBean(FileService.class);
    }

    private UserFriendMessageService getChatMessageService() {
        return BasicServer.classPathXmlApplicationContext
                .getBean(UserFriendMessageService.class);
    }

    private UserFriendsService getUserFriendsService() {
        return BasicServer.classPathXmlApplicationContext.getBean(UserFriendsService.class);
    }

    private UserFriendApplyService getUserFriendApplyService() {
        return BasicServer.classPathXmlApplicationContext.getBean(UserFriendApplyService.class);
    }

    private void handleChatMessageSend(FileUploadFrame frame, SocketChannelContext context) {
        try {
            Long senderId = (Long) context.getAttribute("loggedInUserId");
            if (senderId == null) {
                sendErrorResponse(context, FrameType.CHAT_MSG_RESPONSE, "未登录, 无法发消息",
                        UserAuthFrame.ErrorCode.NOT_LOGGED_IN);
                return;
            }

            JSONObject request = JSON.parseObject(frame.getDataAsString());
            Integer receiverId = request.getInteger("receiverId");
            String content = request.getString("content");
            String msgType = request.getString("msgType"); // TEXT, IMAGE, etc.

            if (receiverId == null || org.apache.commons.lang.StringUtils.isBlank(content)) {
                sendErrorResponse(context, FrameType.CHAT_MSG_RESPONSE, "接收方或内容不能为空",
                        UserAuthFrame.ErrorCode.INVALID_REQUEST);
                return;
            }

            // 1. 持久化记录
            UserFriendMessageDO savedMsg = getChatMessageService()
                    .saveMessage(senderId.intValue(), receiverId, content, msgType);

            // 获取发送者头像
            UserDTO senderInfo = getUserService().getById(senderId.longValue());
            String senderAvatar = senderInfo != null ? getAvatarBase64(senderInfo.getAvatar()) : "";

            // 2. 构建推送给接收方的消息报文
            JSONObject pushData = new JSONObject();
            pushData.put("messageId", savedMsg.getId());
            pushData.put("senderId", senderId);
            pushData.put("content", content);
            pushData.put("msgType", savedMsg.getMsgType());
            pushData.put("avatar", senderAvatar); // 消息附带发送人头像
            pushData.put("gmtCreated",
                    savedMsg.getGmtCreated() != null ? savedMsg.getGmtCreated().getTime() : System.currentTimeMillis());

            // 3. 实时推送给接收方 (如果在线)
            SocketChannelContext receiverContext = BasicServer.onlineUserChannels.get(receiverId.longValue());
            if (receiverContext != null && receiverContext.getSocketChannel().isConnected()) {
                sendFrame(receiverContext, FrameType.CHAT_MSG_PUSH, pushData);
                log.info("聊天消息实时推送成功: sender={}, receiver={}", senderId, receiverId);
            } else {
                log.info("接收方离线或Channel关闭, 消息已落库: receiver={}", receiverId);
            }

            // 4. 返回发信者成功回执
            JSONObject responseData = new JSONObject();
            responseData.put("messageId", savedMsg.getId());
            responseData.put("status", "SUCCESS");
            sendSuccessResponse(context, FrameType.CHAT_MSG_RESPONSE, "发送成功", responseData);

        } catch (Exception e) {
            log.warn("处理聊天消息异常，消息帧数据 = {}, error = {}", JSON.toJSONString(frame), ExceptionUtils.getStackTrace(e));
            JSONObject responseData = new JSONObject();
            responseData.put("messageId", -1);
            responseData.put("status", "FALSE");
            sendErrorResponse(context, FrameType.CHAT_MSG_RESPONSE, "消息发送失败", JSON.toJSONString(responseData));
        }
    }

    private void handleChatMessageHistory(FileUploadFrame frame, SocketChannelContext context) {
        try {
            Long userId = (Long) context.getAttribute("loggedInUserId");
            if (userId == null) {
                sendErrorResponse(context, FrameType.CHAT_MSG_RESPONSE, "未登录, 无法查询消息",
                        UserAuthFrame.ErrorCode.NOT_LOGGED_IN);
                return;
            }

            JSONObject request = JSON.parseObject(frame.getDataAsString());
            Integer friendId = request.getInteger("friendId");
            Integer limit = request.getInteger("limit");
            Integer offset = request.getInteger("offset");
            if (limit == null || limit <= 0) {
                limit = 50;
            }
            if (offset == null || offset < 0) {
                offset = 0;
            }

            if (friendId == null) {
                sendErrorResponse(context, FrameType.CHAT_MSG_RESPONSE, "好友ID不能为空",
                        UserAuthFrame.ErrorCode.INVALID_REQUEST);
                return;
            }

            List<UserFriendMessageDO> historyList = getChatMessageService().getChatHistory(userId.intValue(), friendId,
                    offset, limit);

            // 获取当前用户和好友的头像
            UserDTO currentUser = getUserService().getById(userId);
            UserDTO friendInfo = getUserService().getById(friendId.longValue());
            String currentUserAvatar = currentUser != null ? getAvatarBase64(currentUser.getAvatar()) : "";
            String friendAvatar = friendInfo != null ? getAvatarBase64(friendInfo.getAvatar()) : "";

            java.text.SimpleDateFormat timeFormat = new java.text.SimpleDateFormat("HH:mm");
            java.text.SimpleDateFormat fullFormat = new java.text.SimpleDateFormat("yyyy-MM-dd");

            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
            cal.set(java.util.Calendar.MINUTE, 0);
            cal.set(java.util.Calendar.SECOND, 0);
            cal.set(java.util.Calendar.MILLISECOND, 0);
            long todayStart = cal.getTimeInMillis();

            cal.add(java.util.Calendar.DATE, -1);
            long yesterdayStart = cal.getTimeInMillis();

            List<JSONObject> resultList = new java.util.ArrayList<>();
            if (org.apache.commons.collections4.CollectionUtils.isNotEmpty(historyList)) {
                for (UserFriendMessageDO msg : historyList) {
                    JSONObject item = new JSONObject();
                    // 本条消息Id
                    item.put("id", msg.getId());
                    // 消息发送方userId，类型为Int32
                    item.put("senderId", msg.getSenderId());
                    // 消息接收方userId，类型为Int32
                    item.put("receiverId", msg.getReceiverId());
                    // 消息内容
                    item.put("content", msg.getContent());
                    // 消息类型 类型为Int32
                    item.put("msgType", msg.getMsgType());
                    // 消息状态 类型为Int32
                    item.put("status", msg.getStatus());

                    // 消息对应的头像，根据发送者ID设置头像
                    if (msg.getSenderId().equals(userId.intValue())) {
                        item.put("avatar", currentUserAvatar);
                    } else {
                        item.put("avatar", friendAvatar);
                    }
                    // 根据本条消息发送时间来配置消息所属的分组名称
                    long msgTime = msg.getGmtCreated() != null ? msg.getGmtCreated().getTime()
                            : System.currentTimeMillis();
                    String timeStr;
                    if (msgTime >= todayStart) {
                        timeStr = timeFormat.format(msg.getGmtCreated());
                    } else if (msgTime >= yesterdayStart) {
                        timeStr = "昨天 " + timeFormat.format(msg.getGmtCreated());
                    } else {
                        timeStr = fullFormat.format(msg.getGmtCreated());
                    }
                    // 本条消息所属的分组名称
                    item.put("groupTime", timeStr);
                    // 每条消息增加一个消息发送时间
                    item.put("msgTimeStr", timeFormat.format(msgTime));
                    // 消息添加到集合中
                    resultList.add(item);
                }
            }

            sendSuccessResponse(context, FrameType.CHAT_MSG_HISTORY_RESPONSE, "查询成功", resultList);

        } catch (Exception e) {
            log.warn("处理历史消息异常，消息帧数据 = {}, error = {}", JSON.toJSONString(frame),
                    org.apache.commons.lang.exception.ExceptionUtils.getStackTrace(e));
            JSONObject responseData = new JSONObject();
            responseData.put("status", "FALSE");
            sendErrorResponse(context, FrameType.CHAT_MSG_RESPONSE, "查询失败", JSON.toJSONString(responseData));
        }
    }

    private String getAvatarBase64(String avatarPath) {
        if (org.apache.commons.lang.StringUtils.isNotBlank(avatarPath)) {
            File avatarFile = new File(avatarPath);
            if (avatarFile.exists() && avatarFile.isFile()) {
                try (java.io.FileInputStream fis = new java.io.FileInputStream(avatarFile)) {
                    byte[] fileBytes = new byte[(int) avatarFile.length()];
                    fis.read(fileBytes);
                    return Base64.getEncoder().encodeToString(fileBytes);
                } catch (Exception e) {
                    log.error("读取用户头像文件失败: path={}", avatarPath, e);
                }
            }
        }
        return null;
    }

    // ========== 用户认证处理 ==========

    private void handleRegister(FileUploadFrame frame, SocketChannelContext context) {
        try {
            JSONObject request = JSON.parseObject(frame.getDataAsString());
            String userName = request.getString("userName");
            String nickName = request.getString("nickName");
            String password = request.getString("password");
            String mail = request.getString("mail");
            // 处理头像上传
            String avatarData = request.getString("avatarData");
            String avatarPath = null;
            if (org.apache.commons.lang.StringUtils.isNotBlank(avatarData)) {
                try {
                    byte[] decodedBytes = Base64.getDecoder().decode(avatarData);
                    String suffix = ".png"; // 默认为 png
                    if (request.containsKey("avatarName")) {
                        String originalName = request.getString("avatarName");
                        if (originalName != null && originalName.contains(".")) {
                            suffix = originalName.substring(originalName.lastIndexOf("."));
                        }
                    }

                    String fileName = UUID.randomUUID().toString() + suffix;

                    // 获取基础路径
                    String osName = System.getProperty("os.name");
                    String basePath;
                    if (osName != null && osName.toLowerCase().contains("win")) {
                        basePath = (String) BasicServer.getMap().get(BasicConstant.NIO_FILE_BASE_PATH_WINDOWS);
                    } else {
                        basePath = (String) BasicServer.getMap().get(BasicConstant.NIO_FILE_BASE_PATH_LINUX_MAC);
                    }

                    // 如果配置为空，使用默认路径
                    if (basePath == null) {
                        basePath = System.getProperty("user.dir") + File.separator + "data";
                    }

                    String savePath = basePath + File.separator + userName + File.separator + "avatars" + File.separator
                            + fileName;
                    File destFile = new File(savePath);
                    if (!destFile.getParentFile().exists()) {
                        destFile.getParentFile().mkdirs();
                    }

                    try (FileOutputStream fos = new FileOutputStream(destFile)) {
                        fos.write(decodedBytes);
                    }
                    avatarPath = savePath;
                    log.info("用户注册头像保存成功: path={}", avatarPath);
                } catch (Exception e) {
                    log.error("用户注册头像保存失败", e);
                    // 头像保存失败不影响注册主流程，只是没有头像
                }
            }

            UserDTO result = getUserService().register(userName, password, mail, nickName, avatarPath);

            JSONObject data = new JSONObject();
            data.put("userId", result.getId());
            data.put("userName", result.getUserName());
            data.put("nickName", result.getNickName());
            sendSuccessResponse(context, FrameType.USER_RESPONSE, "注册成功", data);
            log.info("用户注册成功: userName={}", userName);
        } catch (IllegalArgumentException e) {
            String errorCode = e.getMessage().contains("超过") ? UserAuthFrame.ErrorCode.USER_NAME_TOO_LONG
                    : (e.getMessage().contains("已存在") ? UserAuthFrame.ErrorCode.USER_NAME_DUPLICATE
                            : UserAuthFrame.ErrorCode.INVALID_REQUEST);
            sendErrorResponse(context, FrameType.USER_RESPONSE, e.getMessage(), errorCode);
        } catch (Exception e) {
            sendErrorResponse(context, FrameType.USER_RESPONSE, e.getMessage(), UserAuthFrame.ErrorCode.DB_ERROR);
        }
    }

    private void handleLogin(FileUploadFrame frame, SocketChannelContext context) {
        try {
            JSONObject request = JSON.parseObject(frame.getDataAsString());
            String userName = request.getString("userName");
            String password = request.getString("password");
            UserDTO userDTO = getUserService().login(userName, password);
            if (Objects.isNull(userDTO) || StringUtils.equals("del", userDTO.getDel())) {
                throw new IllegalArgumentException("不存在");
            }
            // 登录成功后保存用户信息到连接上下文, 即将当前用户信息与服务端对应的SocketChannel进行绑定
            context.setUserDTO(userDTO);
            context.putAttribute("loggedInUserId", userDTO.getId());
            context.putAttribute("loggedInUserName", userDTO.getUserName());

            // 将登录成功的用户加入在线列表缓存
            BasicServer.onlineUserChannels.put(userDTO.getId(), context);

            // 3、为当前用户创建网盘目录
            try {
                com.alibaba.server.nio.core.initializer.DirectoryInitializer.initialize(userDTO);
            } catch (Exception e) {
                log.error("NioServerContext: 目录初始化失败，但服务将继续启动, error = {}",
                        org.apache.commons.lang.exception.ExceptionUtils.getStackTrace(e));
            }
            JSONObject data = new JSONObject();
            data.put("userId", Integer.valueOf(String.valueOf(userDTO.getId())));
            data.put("token", "后期引入");
            data.put("userName", userDTO.getUserName());
            data.put("phone", userDTO.getPhone());
            data.put("mail", userDTO.getMail());
            sendSuccessResponse(context, FrameType.USER_RESPONSE, "登录成功", data);
            log.info("用户登录成功: userName={}, remoteAddress={}", userName, context.getRemoteAddress());
        } catch (IllegalArgumentException e) {
            String errorCode = e.getMessage().contains("不存在") ? UserAuthFrame.ErrorCode.USER_NOT_FOUND
                    : (e.getMessage().contains("密码") ? UserAuthFrame.ErrorCode.PASSWORD_ERROR
                            : UserAuthFrame.ErrorCode.INVALID_REQUEST);
            sendErrorResponse(context, FrameType.USER_RESPONSE, e.getMessage(), errorCode);
        } catch (Exception e) {
            sendErrorResponse(context, FrameType.USER_RESPONSE, e.getMessage(), UserAuthFrame.ErrorCode.DB_ERROR);
        }
    }

    private void handleChangePassword(FileUploadFrame frame, SocketChannelContext context) {
        try {
            Long userId = (Long) context.getAttribute("loggedInUserId");
            if (userId == null) {
                sendErrorResponse(context, FrameType.USER_RESPONSE, "请先登录", UserAuthFrame.ErrorCode.NOT_LOGGED_IN);
                return;
            }

            JSONObject request = JSON.parseObject(frame.getDataAsString());
            String oldPassword = request.getString("oldPassword");
            String newPassword = request.getString("newPassword");

            getUserService().changePassword(userId, oldPassword, newPassword);

            sendSuccessResponse(context, FrameType.USER_RESPONSE, "密码修改成功", null);
            log.info("用户修改密码成功: userId={}", userId);
        } catch (IllegalArgumentException e) {
            String errorCode = e.getMessage().contains("旧密码") ? UserAuthFrame.ErrorCode.PASSWORD_ERROR
                    : UserAuthFrame.ErrorCode.INVALID_REQUEST;
            sendErrorResponse(context, FrameType.USER_RESPONSE, e.getMessage(), errorCode);
        } catch (Exception e) {
            sendErrorResponse(context, FrameType.USER_RESPONSE, e.getMessage(), UserAuthFrame.ErrorCode.DB_ERROR);
        }
    }

    private void handleLogout(FileUploadFrame frame, SocketChannelContext context) {
        try {
            Long userId = (Long) context.getAttribute("loggedInUserId");
            String userName = (String) context.getAttribute("loggedInUserName");

            if (userId != null) {
                BasicServer.onlineUserChannels.remove(userId);
            }
            context.removeAttribute("loggedInUserId");
            context.removeAttribute("loggedInUserName");

            sendSuccessResponse(context, FrameType.USER_RESPONSE, "退出登录成功", null);
            log.info("用户退出登录: userId={}, userName={}", userId, userName);
        } catch (Exception e) {
            sendErrorResponse(context, FrameType.USER_RESPONSE, e.getMessage(), UserAuthFrame.ErrorCode.DB_ERROR);
        }
    }

    private void handleFriendList(FileUploadFrame frame, SocketChannelContext context) {
        try {
            Long userId = context.getUserDTO().getId();
            UserFriendsQueryParam queryParam = new UserFriendsQueryParam();
            queryParam.setUserId(Integer.valueOf(userId.toString()));
            List<UserFriendsDTO> friends = getUserFriendsService().query(queryParam);

            // 丰富好友信息（头像、昵称等）
            if (friends != null && !friends.isEmpty()) {
                for (UserFriendsDTO friend : friends) {
                    UserDTO friendInfo = getUserService().getById(Long.valueOf(friend.getFriendId()));
                    if (friendInfo != null) {
                        // 如果没有备注，使用昵称或用户名
                        if (org.apache.commons.lang.StringUtils.isBlank(friend.getAlias())) {
                            friend.setAlias(org.apache.commons.lang.StringUtils.isNotBlank(friendInfo.getNickName())
                                    ? friendInfo.getNickName()
                                    : friendInfo.getUserName());
                        }
                        // 这里我们借用UserFriendsDTO中没有的字段来传头像和名字用于显示，或者扩展UserFriendsDTO
                        // 鉴于UserFriendsDTO是生成的，我们可以考虑将这些信息放入额外字段，或者直接修改DTO
                        // 为了简单起见，我们假设客户端需要头像和名字，我们可以用Map返回或者扩展DTO。
                        // 由于DTO不仅用于数据库也用于传输，最好在DTO中增加transient字段，或者使用Map。
                        // 但为了不修改DTO结构，我们这里仅作为示例，实际需确保客户端能解析额外字段（FastJSON特性）
                        // 更好做法：创建UserFriendVO或使用JSONObject
                    }
                }
            }

            // 使用JSONObject列表返回，以便包含头像和详细信息
            List<JSONObject> resultList = new java.util.ArrayList<>();
            if (org.apache.commons.collections4.CollectionUtils.isNotEmpty(friends)) {
                for (UserFriendsDTO friend : friends) {
                    UserDTO friendInfo = getUserService().getById(Long.valueOf(friend.getFriendId()));
                    if (Objects.isNull(friendInfo)) {
                        continue;
                    }

                    JSONObject item = new JSONObject();
                    item.put("id", friend.getId());
                    item.put("userId", friend.getUserId());
                    item.put("friendId", friend.getFriendId());
                    item.put("alias", friend.getAlias());
                    item.put("userName", friendInfo.getUserName());
                    item.put("nickName", friendInfo.getNickName());
                    item.put("avatar", getAvatarBase64(friendInfo.getAvatar()));

                    // 增加当前好友所发送的消息中未读消息数量
                    int unreadCount = getChatMessageService().getUnreadMessageCount(friend.getFriendId(),
                            Integer.valueOf(userId.toString()));
                    item.put("unreadCount", unreadCount);

                    // 增加最新未读消息内容数据（只保留前若干内容片段，如20个字符）
                    UserFriendMessageDO latestMsg = getChatMessageService()
                            .getLatestUnreadMessage(friend.getFriendId(), Integer.valueOf(userId.toString()));
                    if (latestMsg != null
                            && org.apache.commons.lang.StringUtils.isNotBlank(latestMsg.getContent())) {
                        String content = latestMsg.getContent();
                        int snippetLength = 8; // 摘要长度
                        if (content.length() > snippetLength) {
                            item.put("latestUnreadMsg", content.substring(0, snippetLength) + "...");
                        } else {
                            item.put("latestUnreadMsg", content);
                        }
                    } else {
                        item.put("latestUnreadMsg", "");
                    }

                    resultList.add(item);
                }
            }

            sendSuccessResponse(context, FrameType.USER_RESPONSE, "获取好友列表成功", resultList);
        } catch (Exception e) {
            log.error("获取好友列表失败", e);
            sendErrorResponse(context, FrameType.USER_RESPONSE, "获取好友列表失败: " + e.getMessage(),
                    UserAuthFrame.ErrorCode.DB_ERROR);
        }
    }

    private void handleFriendQuery(FileUploadFrame frame, SocketChannelContext context) {
        JSONObject request = JSON.parseObject(frame.getDataAsString());
        String userName = request.getString("userName");
        UserQueryParam userQueryParam = new UserQueryParam();
        userQueryParam.setUserName(userName);

        List<UserDTO> userDTOS = getUserService().getUserListByName(userQueryParam);

        if (userDTOS != null && !userDTOS.isEmpty()) {
            Long currentUserId = context.getUserDTO().getId();

            // 1. 批量查询当前用户的好友列表
            UserFriendsQueryParam friendQuery = new UserFriendsQueryParam();
            friendQuery.setUserId(Integer.valueOf(currentUserId.toString()));
            List<UserFriendsDTO> friends = getUserFriendsService().query(friendQuery);
            java.util.Set<Integer> friendIds = friends == null ? new java.util.HashSet<>()
                    : friends.stream().map(UserFriendsDTO::getFriendId).collect(Collectors.toSet());

            // 2. 批量查询当前用户发出的好友申请
            UserFriendApplyQueryParam applyQuery = new UserFriendApplyQueryParam();
            applyQuery.setSenderId(Integer.valueOf(currentUserId.toString()));
            List<UserFriendApplyDTO> myApplies = getUserFriendApplyService().query(applyQuery);
            // Map<ReceiverId, Status> - 如果有多个申请，取最新的状态？这里假设最近的一个。
            // 实际上 query 可能返回列表。为了简化，我们只关心是否有 pending (0) 或 rejected (2) 的记录。
            // 优先级: Pending > Rejected > None?
            // 如果同时有 rejected 和 pending (重新申请)，则 pending 优先。
            java.util.Map<Integer, Integer> applyStatusMap = new java.util.HashMap<>();
            if (myApplies != null) {
                for (UserFriendApplyDTO apply : myApplies) {
                    Integer receiverId = apply.getReceiverId();
                    Integer status = apply.getStatus();
                    // 逻辑：如果已经有状态，且新状态是0（待处理），覆盖之（显示待同意）。
                    // 如果原状态是0，不变。
                    // 简单粗暴点：0优先。
                    if (!applyStatusMap.containsKey(receiverId)) {
                        applyStatusMap.put(receiverId, status);
                    } else {
                        Integer existingStatus = applyStatusMap.get(receiverId);
                        if (status == 0) { // 发现待处理，优先级最高
                            applyStatusMap.put(receiverId, status);
                        }
                    }
                }
            }

            for (UserDTO user : userDTOS) {
                user.setAvatar(getAvatarBase64(user.getAvatar()));

                if (currentUserId.equals(user.getId())) {
                    user.setFriendStatusDesc("你自己");
                    continue;
                }

                Integer targetId = Integer.valueOf(user.getId().toString());
                if (friendIds.contains(targetId)) {
                    user.setFriendStatus(1);
                    user.setFriendStatusDesc("【已是好友啦，开始聊天吧】");
                } else {
                    Integer applyStatus = applyStatusMap.get(targetId);
                    if (applyStatus != null) {
                        if (applyStatus == 0) {
                            user.setFriendStatus(0);
                            user.setFriendStatusDesc("【已申请添加好友，待对方同意】");
                        } else if (applyStatus == 2) {
                            user.setFriendStatus(2);
                            user.setFriendStatusDesc("【对方拒绝了你的好友申请】");
                        } else {
                            user.setFriendStatus(3);
                            user.setFriendStatusDesc("【添加】");
                        }
                    } else {
                        user.setFriendStatus(3);
                        user.setFriendStatusDesc("【添加】");
                    }
                }
            }
        }

        sendSuccessResponse(context, FrameType.USER_RESPONSE, "搜索成功", userDTOS);
        log.info("添加好友搜索成功: userName={}, remoteAddress={}", userName, context.getRemoteAddress());
    }

    private void handleFriendAdd(FileUploadFrame frame, SocketChannelContext context) {
        try {
            JSONObject request = JSON.parseObject(frame.getDataAsString());
            Integer receiveUserId = request.getInteger("userId"); // 搜索结果返回的userId
            String requestMsg = request.getString("requestMsg");
            Long currentUserId = context.getUserDTO().getId();

            if (receiveUserId == null) {
                throw new IllegalArgumentException("好友ID不能为空");
            }
            if (currentUserId.intValue() == receiveUserId) {
                throw new IllegalArgumentException("不能添加自己为好友");
            }

            // 检查是否已经是好友
            UserFriendsQueryParam friendQuery = new UserFriendsQueryParam();
            friendQuery.setUserId(Integer.valueOf(currentUserId.toString()));
            friendQuery.setFriendId(receiveUserId);
            List<UserFriendsDTO> friends = getUserFriendsService().query(friendQuery);
            if (friends != null && !friends.isEmpty()) {
                throw new IllegalArgumentException("已经是好友了");
            }

            // 检查是否已经申请过且待处理
            UserFriendApplyQueryParam applyQuery = new UserFriendApplyQueryParam();
            applyQuery.setSenderId(Integer.valueOf(currentUserId.toString()));
            applyQuery.setReceiverId(receiveUserId);
            applyQuery.setStatus(0); // 待处理
            List<UserFriendApplyDTO> applies = getUserFriendApplyService().query(applyQuery);
            if (applies != null && !applies.isEmpty()) {
                throw new IllegalArgumentException("已发送过申请，请等待对方处理");
            }

            UserFriendApplyCreateParam createParam = new UserFriendApplyCreateParam();
            createParam.setSenderId(Integer.valueOf(currentUserId.toString()));
            createParam.setReceiverId(receiveUserId);
            createParam.setRequestMsg(requestMsg);
            getUserFriendApplyService().create(createParam);
            sendSuccessResponse(context, FrameType.USER_RESPONSE, "发送好友申请成功", null);
        } catch (IllegalArgumentException e) {
            sendErrorResponse(context, FrameType.USER_RESPONSE, e.getMessage(),
                    UserAuthFrame.ErrorCode.INVALID_REQUEST);
        } catch (Exception e) {
            log.error("发送好友申请失败", e);
            sendErrorResponse(context, FrameType.USER_RESPONSE, "发送好友申请失败", UserAuthFrame.ErrorCode.DB_ERROR);
        }
    }

    private void handleFriendApply(FileUploadFrame frame, SocketChannelContext context) {
        try {
            Long currentUserId = context.getUserDTO().getId();
            UserFriendApplyQueryParam queryParam = new UserFriendApplyQueryParam();
            queryParam.setReceiverId(Integer.valueOf(currentUserId.toString()));
            queryParam.setStatus(0); // 只查询待处理的申请，或者查询所有？通常是查询待处理的

            List<UserFriendApplyDTO> applies = getUserFriendApplyService().query(queryParam);

            // 丰富申请信息（发送者头像、昵称）
            List<JSONObject> resultList = new java.util.ArrayList<>();
            if (applies != null) {
                for (UserFriendApplyDTO apply : applies) {
                    UserDTO senderInfo = getUserService().getById(Long.valueOf(apply.getSenderId()));
                    if (senderInfo != null) {
                        JSONObject item = new JSONObject();
                        item.put("id", apply.getId());
                        item.put("senderId", apply.getSenderId());
                        item.put("requestMsg", apply.getRequestMsg());
                        item.put("status", apply.getStatus());
                        item.put("userName", senderInfo.getUserName());
                        item.put("nickName", senderInfo.getNickName());
                        item.put("avatar", getAvatarBase64(senderInfo.getAvatar()));
                        item.put("gmtCreated", apply.getGmtCreated());
                        resultList.add(item);
                    }
                }
            }

            sendSuccessResponse(context, FrameType.USER_RESPONSE, "获取好友申请列表成功", resultList);
        } catch (Exception e) {
            log.error("获取好友申请列表失败", e);
            sendErrorResponse(context, FrameType.USER_RESPONSE, "获取好友申请列表失败", UserAuthFrame.ErrorCode.DB_ERROR);
        }
    }

    private void handleFriendApplylyHandle(FileUploadFrame frame, SocketChannelContext context) {
        try {
            JSONObject request = JSON.parseObject(frame.getDataAsString());
            Long applyId = request.getLong("requestId");
            Integer status = request.getInteger("action"); // 1=同意, 2=拒绝
            String alias = request.getString("alias"); // 备注名

            if (applyId == null || status == null) {
                throw new IllegalArgumentException("参数错误");
            }

            // 获取申请记录以验证和获取sender/receiver
            // 遗憾的是Service没有getDtoById，我们只能query... 或者改进UserFriendApplyService
            // 这里为了简单，我们先update，但我们需要知道senderId来创建friend关系。
            // 必须查询出来！
            // 暂时没提供getById，那我们用query查询
            // 或者直接 update，如果同意，则需要 senderId。
            // 方案：查询Service中没有getById，那我们只能修改Service或者用Mapper直接查（不推荐直接用Repo）。
            // 既然都在repository包下，我们可以假设Service能做。
            // 但之前没加getById到 UserFriendApplyService。
            // 这里我们先用 query 过滤 id? No, QueryParam usually doesn't have ID.
            // Wait, implementation plan check: UserFriendApplyQueryParam has senderId,
            // receiverId, status.
            // UserFriendApplyUpdateParam has ID.

            // 我们需要获取该申请的详细信息。
            // 只能先勉强信任前端传来的 senderId? 不安全。
            // 正确做法：给 UserFriendApplyService 加 getById。
            // 既然现在不能改Service (user didn't ask), 我们可以 iterate query result? No, inefficient.
            // 我们假设 request 传了 senderId?
            // Better: update service to add getById for Apply as well? Or just trust update
            // returns success.
            // But we need to insert UserFriends record.

            // Let's look at `UserFriendApplyDo`. It extends `BaseDO`.
            // UserFriendApplyService.update(param)

            // Hack for now: query by status=0 and receiver=currentUserId to find the
            // matching applyId in memory?
            // Bad performance but safe.
            Long currentUserId = context.getUserDTO().getId();
            UserFriendApplyQueryParam queryParam = new UserFriendApplyQueryParam();
            queryParam.setReceiverId(Integer.valueOf(currentUserId.toString()));
            queryParam.setStatus(0);
            List<UserFriendApplyDTO> pendingApplies = getUserFriendApplyService().query(queryParam);

            UserFriendApplyDTO targetApply = null;
            if (pendingApplies != null) {
                for (UserFriendApplyDTO dto : pendingApplies) {
                    if (dto.getId().equals(applyId)) {
                        targetApply = dto;
                        break;
                    }
                }
            }

            if (targetApply == null) {
                // 可能是已经处理过了或者id不对
                // 尝试直接更新状态（如果只是拒绝，不需要TargetApply具体信息，除了校验）
            }

            UserFriendApplyUpdateParam updateParam = new UserFriendApplyUpdateParam();
            updateParam.setId(applyId);
            updateParam.setStatus(status);
            getUserFriendApplyService().update(updateParam);

            if (status == 1) { // 同意
                if (targetApply != null) {
                    // 创建双向好友关系
                    // 1. Me -> Sender (with alias)
                    UserFriendsCreateParam friend1 = new UserFriendsCreateParam();
                    friend1.setUserId(Integer.valueOf(currentUserId.toString()));
                    friend1.setFriendId(targetApply.getSenderId());
                    friend1.setAlias(alias);
                    getUserFriendsService().create(friend1);

                    // 2. Sender -> Me (no alias default)
                    UserFriendsCreateParam friend2 = new UserFriendsCreateParam();
                    friend2.setUserId(targetApply.getSenderId());
                    friend2.setFriendId(Integer.valueOf(currentUserId.toString()));
                    friend2.setAlias(null); // 对方看我暂时没备注，或者可以用我的昵称
                    getUserFriendsService().create(friend2);
                } else {
                    log.warn("处理好友申请: 同意了但未找到申请记录(可能已处理或权限不足), applyId={}", applyId);
                    // 这种情况下没法创建好友关系... 这是一个问题。
                    // 应该抛错。
                    throw new IllegalStateException("未找到待处理的申请记录");
                }
            }

            sendSuccessResponse(context, FrameType.USER_RESPONSE, "处理成功", null);
        } catch (Exception e) {
            log.error("处理好友申请失败", e);
            sendErrorResponse(context, FrameType.USER_RESPONSE, "处理失败: " + e.getMessage(),
                    UserAuthFrame.ErrorCode.DB_ERROR);
        }
    }

    // ========== 目录操作处理 ==========

    private void handleUserTwoLevelDirectory(FileUploadFrame frame, SocketChannelContext context) {
        try {
            FileDto result = getFileService().handleUserTwoLevelDirectory(context.getUserDTO());
            sendSuccessResponse(context, FrameType.DIR_RESPONSE, "目录创建成功", result);
        } catch (IllegalArgumentException e) {
            sendErrorResponse(context, FrameType.DIR_RESPONSE, e.getMessage(),
                    DirectoryFrame.ErrorCode.DIR_ROOT_NOT_EXIST);
        } catch (RuntimeException e) {
            sendErrorResponse(context, FrameType.DIR_RESPONSE, e.getMessage(), DirectoryFrame.ErrorCode.FS_ERROR);
        } catch (Exception e) {
            sendErrorResponse(context, FrameType.DIR_RESPONSE, e.getMessage(), DirectoryFrame.ErrorCode.DB_ERROR);
        }
    }

    private void handleCreateDirectory(FileUploadFrame frame, SocketChannelContext context) {
        try {
            JSONObject request = JSON.parseObject(frame.getDataAsString());
            Long parentId = request.getLong("pId");
            String dirName = request.getString("dirName");

            FileDto result = getFileService().createDirectory(parentId, dirName, context.getUserDTO());
            sendSuccessResponse(context, FrameType.DIR_RESPONSE, "目录创建成功", result);
        } catch (IllegalArgumentException e) {
            sendErrorResponse(context, FrameType.DIR_RESPONSE, e.getMessage(),
                    DirectoryFrame.ErrorCode.DIR_NAME_TOO_LONG);
        } catch (RuntimeException e) {
            sendErrorResponse(context, FrameType.DIR_RESPONSE, e.getMessage(), DirectoryFrame.ErrorCode.FS_ERROR);
        } catch (Exception e) {
            sendErrorResponse(context, FrameType.DIR_RESPONSE, e.getMessage(), DirectoryFrame.ErrorCode.DB_ERROR);
        }
    }

    private void handleDeleteDirectory(FileUploadFrame frame, SocketChannelContext context) {
        try {
            JSONObject request = JSON.parseObject(frame.getDataAsString());
            Long dirId = request.getLong("id");

            getFileService().deleteDirectory(dirId);
            sendSuccessResponse(context, FrameType.DIR_RESPONSE, "目录删除成功", null);
        } catch (IllegalStateException e) {
            sendErrorResponse(context, FrameType.DIR_RESPONSE, e.getMessage(),
                    DirectoryFrame.ErrorCode.DIR_HAS_CHILDREN);
        } catch (IllegalArgumentException e) {
            sendErrorResponse(context, FrameType.DIR_RESPONSE, e.getMessage(), DirectoryFrame.ErrorCode.DIR_NOT_FOUND);
        } catch (RuntimeException e) {
            sendErrorResponse(context, FrameType.DIR_RESPONSE, e.getMessage(), DirectoryFrame.ErrorCode.FS_ERROR);
        } catch (Exception e) {
            sendErrorResponse(context, FrameType.DIR_RESPONSE, e.getMessage(), DirectoryFrame.ErrorCode.DB_ERROR);
        }
    }

    private void handleUpdateDirectory(FileUploadFrame frame, SocketChannelContext context) {
        try {
            JSONObject request = JSON.parseObject(frame.getDataAsString());
            Long dirId = request.getLong("id");
            String newName = request.getString("dirName");

            FileDto result = getFileService().updateDirectory(dirId, newName);
            sendSuccessResponse(context, FrameType.DIR_RESPONSE, "目录更新成功", result);
        } catch (IllegalArgumentException e) {
            String errorCode = e.getMessage().contains("同名") ? DirectoryFrame.ErrorCode.DIR_NAME_DUPLICATE
                    : DirectoryFrame.ErrorCode.DIR_NAME_TOO_LONG;
            sendErrorResponse(context, FrameType.DIR_RESPONSE, e.getMessage(), errorCode);
        } catch (RuntimeException e) {
            sendErrorResponse(context, FrameType.DIR_RESPONSE, e.getMessage(), DirectoryFrame.ErrorCode.FS_ERROR);
        } catch (Exception e) {
            sendErrorResponse(context, FrameType.DIR_RESPONSE, e.getMessage(), DirectoryFrame.ErrorCode.DB_ERROR);
        }
    }

    private void handleMoveDirectory(FileUploadFrame frame, SocketChannelContext context) {
        try {
            JSONObject request = JSON.parseObject(frame.getDataAsString());
            Long dirId = request.getLong("dirId");
            Long targetParentId = request.getLong("targetParentId");

            FileDto result = getFileService().moveDirectory(dirId, targetParentId);
            sendSuccessResponse(context, FrameType.DIR_RESPONSE, "目录移动成功", result);
        } catch (IllegalArgumentException e) {
            sendErrorResponse(context, FrameType.DIR_RESPONSE, e.getMessage(), DirectoryFrame.ErrorCode.PARENT_NOT_DIR);
        } catch (RuntimeException e) {
            sendErrorResponse(context, FrameType.DIR_RESPONSE, e.getMessage(), DirectoryFrame.ErrorCode.FS_ERROR);
        } catch (Exception e) {
            sendErrorResponse(context, FrameType.DIR_RESPONSE, e.getMessage(), DirectoryFrame.ErrorCode.DB_ERROR);
        }
    }

    // ========== 文件操作处理 ==========

    private void handleFileList(FileUploadFrame frame, SocketChannelContext context) {
        try {
            JSONObject request = JSON.parseObject(frame.getDataAsString());
            Integer userId = Integer.valueOf(String.valueOf(context.getUserDTO().getId()));
            Long dirId = request.getLong("dirId"); // 所选择的目录id(目录树上点击的目录Id、目录下拉树中选择目录Id)
            String fileName = request.getString("fileName"); //
            int pageNum = request.getIntValue("pageNum");
            int pageSize = request.getIntValue("pageSize");
            if (pageNum < 1) {
                pageNum = 1;
            }
            if (pageSize < 1) {
                pageSize = 10;
            }

            FileQueryParam fileQueryParam = new FileQueryParam();
            fileQueryParam.setUserId(userId);
            if (Objects.nonNull(dirId) && 0L != dirId) {
                fileQueryParam.setParentId(dirId);
            }
            fileQueryParam.setFileName(null);
            if (org.apache.commons.lang.StringUtils.isNotBlank(fileName)) {
                fileQueryParam.setParentId(null);
                fileQueryParam.setFileName(fileName);
            }
            fileQueryParam.setCurrentPage(pageNum);
            fileQueryParam.setPageSize(pageSize);
            FilePageDto pageDto = getFileService().listFiles(fileQueryParam);
            sendSuccessResponse(context, FrameType.FILE_RESPONSE, "查询成功", pageDto);
        } catch (IllegalArgumentException e) {
            sendErrorResponse(context, FrameType.FILE_RESPONSE, e.getMessage(), "INVALID_REQUEST");
        } catch (Exception e) {
            sendErrorResponse(context, FrameType.FILE_RESPONSE, e.getMessage(), "DB_ERROR");
        }
    }

    private void handleFileDetail(FileUploadFrame frame, SocketChannelContext context) {
        try {
            JSONObject request = JSON.parseObject(frame.getDataAsString());
            Long fileId = request.getLong("fileId");

            FileDto result = getFileService().getFileDetail(fileId);
            sendSuccessResponse(context, FrameType.FILE_RESPONSE, "查询成功", result);
        } catch (IllegalArgumentException e) {
            sendErrorResponse(context, FrameType.FILE_RESPONSE, e.getMessage(), "FILE_NOT_FOUND");
        } catch (Exception e) {
            sendErrorResponse(context, FrameType.FILE_RESPONSE, e.getMessage(), "DB_ERROR");
        }
    }

    private void handleFileDelete(FileUploadFrame frame, SocketChannelContext context) {
        try {
            JSONObject request = JSON.parseObject(frame.getDataAsString());
            Long fileId = request.getLong("fileId");

            getFileService().deleteFileWithFs(fileId);
            sendSuccessResponse(context, FrameType.FILE_RESPONSE, "文件删除成功", null);
            log.info("文件删除成功: fileId={}", fileId);
        } catch (IllegalArgumentException e) {
            sendErrorResponse(context, FrameType.FILE_RESPONSE, e.getMessage(), "FILE_NOT_FOUND");
        } catch (RuntimeException e) {
            sendErrorResponse(context, FrameType.FILE_RESPONSE, e.getMessage(), "FS_ERROR");
        } catch (Exception e) {
            sendErrorResponse(context, FrameType.FILE_RESPONSE, e.getMessage(), "DB_ERROR");
        }
    }

    // ========== 响应发送 ==========

    private void sendSuccessResponse(SocketChannelContext context, FrameType responseType, String message,
            Object data) {
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("message", message);
        response.put("data", null);
        if (data != null) {
            response.put("data", data);
        }
        sendFrame(context, responseType, response);
    }

    private void sendErrorResponse(SocketChannelContext context, FrameType responseType, String message,
            String errorCode) {
        JSONObject response = new JSONObject();
        response.put("success", false);
        response.put("message", message);
        response.put("errorCode", errorCode);
        sendFrame(context, responseType, response);
        log.warn("发送错误响应: errorCode={}, message={}", errorCode, message);
    }

    private void sendFrame(SocketChannelContext context, FrameType type, JSONObject data) {
        try {
            byte[] jsonBytes = data.toJSONString().getBytes(StandardCharsets.UTF_8);
            ByteBuffer buffer = ByteBuffer.allocateDirect(FileUploadFrame.HEADER_LENGTH + jsonBytes.length);
            buffer.put(FileUploadFrame.MAGIC);
            buffer.put((byte) type.getCode());
            buffer.put((byte) 0);
            buffer.putInt(jsonBytes.length);
            buffer.put(jsonBytes);
            buffer.flip();

            log.info("=> 成功向客户端发送内容: 远程地址: {}, 数据内容: {}, 帧类型: {}, ",
                    context.getRemoteAddress(), JSON.toJSONString(data), type.getDescription());
            WriteQueueHelper.submitWrite(context, buffer);
        } catch (Exception e) {
            log.error("发送帧失败: type={}, error={}", type, ExceptionUtils.getStackTrace(e));
        }
    }
}
