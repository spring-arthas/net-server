package com.alibaba.server.nio.service.file.handler;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
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
import com.alibaba.server.nio.repository.file.service.FileService;
import com.alibaba.server.nio.repository.file.service.dto.FileDto;
import com.alibaba.server.nio.repository.file.service.dto.FilePageDto;
import com.alibaba.server.nio.repository.file.service.param.FileQueryParam;
import com.alibaba.server.nio.repository.user.service.UserService;
import com.alibaba.server.nio.repository.user.service.dto.UserDTO;
import com.alibaba.server.nio.service.api.AbstractChannelHandler;
import com.alibaba.server.nio.service.file.parser.FrameParser;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

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
    private static final ConcurrentHashMap<String, FrameParser> parserMap = new ConcurrentHashMap<>();

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
        FrameParser parser = getOrCreateParser(socketChannelContext);

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
    private FrameParser getOrCreateParser(SocketChannelContext socketChannelContext) {
        String remoteAddress = socketChannelContext.getRemoteAddress();
        return parserMap.computeIfAbsent(remoteAddress, k -> {
            log.debug("为通道 {} 创建新的 FrameParser", remoteAddress);
            return new FrameParser();
        });
    }

    /**
     * 处理单个帧（统一分发）
     */
    private void processFrame(FileUploadFrame frame, SocketChannelContext context) {
        FrameType type = frame.getType();
        log.debug("收到帧: type={}，数据内容={}", type, JSON.toJSONString(frame.getDataAsString()));

        try {
            switch (type) {
                // ========== 用户认证帧 ==========
                case USER_REGISTER_REQ: // 用户注册请求
                    handleRegister(frame, context);
                    break;
                case USER_LOGIN_REQ: // 用户登录请求
                    handleLogin(frame, context);
                    break;
                case USER_CHANGE_PWD_REQ:  // 用户修改密码请求
                    handleChangePassword(frame, context);
                    break;
                case USER_LOGOUT_REQ: // 用户退出登录请求
                    handleLogout(frame, context);
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
                case FILE_DETAIL_REQ:
                    handleFileDetail(frame, context);
                    break;
                case FILE_DELETE_REQ:
                    handleFileDelete(frame, context);
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

    // ========== 用户认证处理 ==========

    private void handleRegister(FileUploadFrame frame, SocketChannelContext context) {
        try {
            JSONObject request = JSON.parseObject(frame.getDataAsString());
            String userName = request.getString("userName");
            String password = request.getString("password");
            String mail = request.getString("mail");

            UserDTO result = getUserService().register(userName, password, mail);

            JSONObject data = new JSONObject();
            data.put("userId", result.getId());
            data.put("userName", result.getUserName());

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

            context.removeAttribute("loggedInUserId");
            context.removeAttribute("loggedInUserName");

            sendSuccessResponse(context, FrameType.USER_RESPONSE, "退出登录成功", null);
            log.info("用户退出登录: userId={}, userName={}", userId, userName);
        } catch (Exception e) {
            sendErrorResponse(context, FrameType.USER_RESPONSE, e.getMessage(), UserAuthFrame.ErrorCode.DB_ERROR);
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
            if (pageNum < 1) { pageNum = 1; }
            if (pageSize < 1) { pageSize = 10; }

            FileQueryParam fileQueryParam = new FileQueryParam();
            fileQueryParam.setUserId(userId);
            if(Objects.nonNull(dirId) && 0L != dirId) {
                fileQueryParam.setParentId(dirId);
            }
            fileQueryParam.setFileName(null);
            if(org.apache.commons.lang.StringUtils.isNotBlank(fileName)) {
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
