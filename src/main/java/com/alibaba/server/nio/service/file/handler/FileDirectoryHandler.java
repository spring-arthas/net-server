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
import com.alibaba.server.nio.repository.file.service.FileService;
import com.alibaba.server.nio.repository.file.service.dto.FileDto;
import com.alibaba.server.nio.service.api.AbstractChannelHandler;
import com.alibaba.server.nio.service.file.parser.FrameParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文件目录处理器
 * 
 * 支持功能：
 * 1. 目录创建/删除/更新/移动
 * 2. 文件上传到指定目录
 * 
 * 协议复用 FileUploadFrame 格式，错误通过响应帧返回（不断开连接）
 * 
 * @author spring
 */
@Slf4j
public class FileDirectoryHandler extends AbstractChannelHandler {

    /**
     * 帧解析器缓存
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
            log.debug("为通道 {} 创建新的 FileUploadFrameParser", remoteAddress);
            return new FrameParser();
        });
    }

    /**
     * 处理单个帧
     */
    private void processFrame(FileUploadFrame frame, SocketChannelContext context) {
        FrameType type = frame.getType();
        log.debug("收到目录帧: type={}", type);

        try {
            switch (type) {
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
                default:
                    log.warn("未处理的目录帧类型: {}", type);
            }
        } catch (Exception e) {
            log.error("处理目录帧异常: type={}", type, e);
            sendErrorResponse(context, e.getMessage(), "INTERNAL_ERROR");
        }
    }

    /**
     * 获取FileService
     */
    private FileService getFileService() {
        return BasicServer.classPathXmlApplicationContext.getBean(FileService.class);
    }

    /**
     * 处理创建目录请求
     */
    private void handleCreateDirectory(FileUploadFrame frame, SocketChannelContext context) {
        try {
            JSONObject request = JSON.parseObject(frame.getDataAsString());
            Long parentId = request.getLong("parentId");
            String dirName = request.getString("dirName");

            FileDto result = getFileService().createDirectory(parentId, dirName);
            sendSuccessResponse(context, "目录创建成功", result);
        } catch (IllegalArgumentException e) {
            sendErrorResponse(context, e.getMessage(), DirectoryFrame.ErrorCode.DIR_NAME_TOO_LONG);
        } catch (RuntimeException e) {
            sendErrorResponse(context, e.getMessage(), DirectoryFrame.ErrorCode.FS_ERROR);
        } catch (Exception e) {
            sendErrorResponse(context, e.getMessage(), DirectoryFrame.ErrorCode.DB_ERROR);
        }
    }

    /**
     * 处理删除目录请求
     */
    private void handleDeleteDirectory(FileUploadFrame frame, SocketChannelContext context) {
        try {
            JSONObject request = JSON.parseObject(frame.getDataAsString());
            Long dirId = request.getLong("dirId");

            getFileService().deleteDirectory(dirId);
            sendSuccessResponse(context, "目录删除成功", null);
        } catch (IllegalStateException e) {
            sendErrorResponse(context, e.getMessage(), DirectoryFrame.ErrorCode.DIR_HAS_CHILDREN);
        } catch (IllegalArgumentException e) {
            sendErrorResponse(context, e.getMessage(), DirectoryFrame.ErrorCode.DIR_NOT_FOUND);
        } catch (RuntimeException e) {
            sendErrorResponse(context, e.getMessage(), DirectoryFrame.ErrorCode.FS_ERROR);
        } catch (Exception e) {
            sendErrorResponse(context, e.getMessage(), DirectoryFrame.ErrorCode.DB_ERROR);
        }
    }

    /**
     * 处理更新目录请求
     */
    private void handleUpdateDirectory(FileUploadFrame frame, SocketChannelContext context) {
        try {
            JSONObject request = JSON.parseObject(frame.getDataAsString());
            Long dirId = request.getLong("dirId");
            String newName = request.getString("newName");

            FileDto result = getFileService().updateDirectory(dirId, newName);
            sendSuccessResponse(context, "目录更新成功", result);
        } catch (IllegalArgumentException e) {
            String errorCode = e.getMessage().contains("同名") ? DirectoryFrame.ErrorCode.DIR_NAME_DUPLICATE
                    : DirectoryFrame.ErrorCode.DIR_NAME_TOO_LONG;
            sendErrorResponse(context, e.getMessage(), errorCode);
        } catch (RuntimeException e) {
            sendErrorResponse(context, e.getMessage(), DirectoryFrame.ErrorCode.FS_ERROR);
        } catch (Exception e) {
            sendErrorResponse(context, e.getMessage(), DirectoryFrame.ErrorCode.DB_ERROR);
        }
    }

    /**
     * 处理移动目录请求
     */
    private void handleMoveDirectory(FileUploadFrame frame, SocketChannelContext context) {
        try {
            JSONObject request = JSON.parseObject(frame.getDataAsString());
            Long dirId = request.getLong("dirId");
            Long targetParentId = request.getLong("targetParentId");

            FileDto result = getFileService().moveDirectory(dirId, targetParentId);
            sendSuccessResponse(context, "目录移动成功", result);
        } catch (IllegalArgumentException e) {
            sendErrorResponse(context, e.getMessage(), DirectoryFrame.ErrorCode.PARENT_NOT_DIR);
        } catch (RuntimeException e) {
            sendErrorResponse(context, e.getMessage(), DirectoryFrame.ErrorCode.FS_ERROR);
        } catch (Exception e) {
            sendErrorResponse(context, e.getMessage(), DirectoryFrame.ErrorCode.DB_ERROR);
        }
    }

    /**
     * 发送成功响应
     */
    private void sendSuccessResponse(SocketChannelContext context, String message, Object data) {
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("message", message);
        if (data != null) {
            response.put("data", data);
        }
        sendFrame(context, FrameType.DIR_RESPONSE, response);
    }

    /**
     * 发送错误响应（不断开连接）
     */
    private void sendErrorResponse(SocketChannelContext context, String message, String errorCode) {
        JSONObject response = new JSONObject();
        response.put("success", false);
        response.put("message", message);
        response.put("errorCode", errorCode);
        sendFrame(context, FrameType.DIR_RESPONSE, response);
        log.warn("发送错误响应: errorCode={}, message={}", errorCode, message);
    }

    /**
     * 发送帧
     */
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

            WriteQueueHelper.submitWrite(context, buffer);
        } catch (Exception e) {
            log.error("发送帧失败: type={}", type, e);
        }
    }
}
