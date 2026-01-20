package com.alibaba.server.nio.service.file.handler;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.server.nio.core.server.BasicServer;
import com.alibaba.server.nio.core.server.NioServerContext;
import com.alibaba.server.nio.handler.event.AbstractEventHandler;
import com.alibaba.server.nio.handler.pipe.ChannelContext;
import com.alibaba.server.nio.handler.pipe.standard.SimpleChannelContext;
import com.alibaba.server.nio.model.ChannelEventModel;
import com.alibaba.server.nio.model.SocketChannelContext;
import com.alibaba.server.nio.model.TransportDataModel;
import com.alibaba.server.nio.model.file.FileUploadContext;
import com.alibaba.server.nio.model.file.FileUploadFrame;
import com.alibaba.server.nio.model.file.FileUploadFrame.FrameType;
import com.alibaba.server.nio.repository.file.service.FileService;
import com.alibaba.server.nio.repository.file.service.param.FileQueryParam;
import com.alibaba.server.nio.service.api.AbstractChannelHandler;
import com.alibaba.server.nio.service.file.config.FileUploadConfig;
import com.alibaba.server.nio.service.file.parser.FrameParser;
import com.alibaba.server.nio.service.ratelimit.TokenBucketRateLimiter;
import com.alibaba.server.util.LocalTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文件上传处理器
 * 使用 FileUploadFrameParser 进行渐进式协议解析，处理 TCP 粘包/半包问题
 * 
 * 业务流程：
 * 1. 接收元数据帧（META_FRAME）：解析文件信息，创建数据库记录，打开文件通道
 * 2. 接收数据帧（DATA_FRAME）：将文件字节数据写入本地文件
 * 3. 接收结束帧（END_FRAME）：关闭文件通道，更新数据库状态，通知客户端
 * 
 * @author YSFY
 */
@Slf4j
@SuppressWarnings("all")
public class FileUploadHandler extends AbstractChannelHandler {

    /**
     * 文件上传配置
     */
    private static FileUploadConfig config;

    /**
     * 全局速率限制器（控制服务器总上传带宽）
     */
    private static TokenBucketRateLimiter globalRateLimiter;

    /**
     * 并发控制信号量（限制最大同时上传数）
     */
    private static java.util.concurrent.Semaphore uploadSemaphore;

    /**
     * 静态初始化块：初始化全局限流器和信号量
     */
    static {
        try {
            config = BasicServer.classPathXmlApplicationContext.getBean(FileUploadConfig.class);
            globalRateLimiter = new TokenBucketRateLimiter(
                config.getGlobalRateBps(), 
                config.getGlobalBucketCapacity()
            );
            uploadSemaphore = new java.util.concurrent.Semaphore(config.getMaxConcurrentUploads());
            log.info("文件上传控制器初始化完成 - 全局速率: {} B/s, 单连接速率: {} B/s, 最大并发: {}",
                config.getGlobalRateBps(), config.getPerConnectionRateBps(), config.getMaxConcurrentUploads());
        } catch (Exception e) {
            log.error("初始化文件上传配置失败，使用默认值", e);
            config = new FileUploadConfig(); // 使用默认配置
            globalRateLimiter = new TokenBucketRateLimiter(
                config.getGlobalRateBps(),
                config.getGlobalBucketCapacity()
            );
            uploadSemaphore = new java.util.concurrent.Semaphore(config.getMaxConcurrentUploads());
        }
    }

    /**
     * 上传上下文缓存：taskId -> FileUploadContext
     */
    private static final ConcurrentHashMap<String, FileUploadContext> uploadContextMap = new ConcurrentHashMap<>();

    /**
     * 帧解析器缓存：remoteAddress -> FileUploadFrameParser
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

        // 检查 handlerType，非 UPLOAD 则直接返回，让 Pipeline 继续执行下一个 Handler
        if (!"UPLOAD".equals(socketChannelContext.getHandlerType())) {
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
                handleFrame(frame, socketChannelContext, simpleChannelContext);
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
     * 处理单个完整的帧
     */
    private void handleFrame(FileUploadFrame frame,
            SocketChannelContext socketChannelContext,
            SimpleChannelContext simpleChannelContext) throws IOException {

        if (frame.getType() == null) {
            log.warn("帧类型为空，跳过处理");
            return;
        }

        log.debug("处理帧: type={}, dataLength={}", frame.getType(), frame.getDataLength());

        switch (frame.getType()) {
            case META_FRAME:
                handleMetaFrame(frame, socketChannelContext, simpleChannelContext);
                break;

            case DATA_FRAME:
                handleDataFrame(frame, socketChannelContext);
                break;

            case END_FRAME:
                handleEndFrame(frame, socketChannelContext, simpleChannelContext);
                break;

            default:
                log.warn("未知帧类型: {}", frame.getType());
                break;
        }
    }

    /**
     * 处理元数据帧（META_FRAME）
     * 解析文件信息，创建数据库记录，打开文件通道
     */
    private void handleMetaFrame(FileUploadFrame frame,
            SocketChannelContext socketChannelContext,
            SimpleChannelContext simpleChannelContext) throws IOException {
        try {
            // 1. 解析 JSON 元数据
            String jsonData = frame.getDataAsString();
            JSONObject meta = JSON.parseObject(jsonData);

            String fileName = meta.getString("fileName");
            long fileSize = meta.getLongValue("fileSize");
            String fileType = meta.getString("fileType");
            Long dirId = meta.getLong("dirId");
            Long userId = meta.getLong("userId");

            log.info(
                    "[ {} ] FileHeadDecodeHandler | --> 接收到元数据帧: fileName={}, fileSize={}, fileType={}, dirId={}, userId={}",
                    LocalTime.formatDate(LocalDateTime.now()), fileName, fileSize, fileType, dirId, userId);

            // 2. 校验目录是否存在且为目录类型
            FileService fileService = BasicServer.classPathXmlApplicationContext.getBean(FileService.class);
            String dirPath = null;
            if (dirId != null) {
                dirPath = fileService.validateDirectory(dirId);
                if (dirPath == null) {
                    log.error("目录不存在或类型错误: dirId={}", dirId);
                    sendAckFrame(socketChannelContext, null, "error", "目录不存在或类型错误");
                    return;
                }
            }

            // 3. 尝试获取上传并发许可（非阻塞）
            if (!uploadSemaphore.tryAcquire()) {
                int currentUploads = config.getMaxConcurrentUploads() - uploadSemaphore.availablePermits();
                log.warn("上传并发数已达上限，拒绝新上传请求: 当前并发={}/{}, fileName={}, remoteAddress={}",
                        currentUploads, config.getMaxConcurrentUploads(), fileName, socketChannelContext.getRemoteAddress());
                sendAckFrame(socketChannelContext, null, "error", 
                    "服务器上传繁忙，当前并发上传数已达到上限，请稍后重试");
                return;
            }

            // 4. 创建上传上下文
            FileUploadContext uploadContext = new FileUploadContext();
            uploadContext.setTaskId(FileUploadContext.generateTaskId());
            uploadContext.setFileName(fileName);
            uploadContext.setFileSize(fileSize);
            uploadContext.setFileType(fileType);
            uploadContext.setSemaphoreAcquired(true); // 标记已获取许可
            // 关联远程客户端连接，用于后续精确匹配
            uploadContext.setRemoteAddress(socketChannelContext.getRemoteAddress());
            // 如果有目录，使用目录路径存储文件
            if (dirPath != null) {
                uploadContext.setBasePath(dirPath);
            }

            // 5. 创建数据库记录（状态：上传中）
            try {
                FileQueryParam fileQueryParam = new FileQueryParam();
                fileQueryParam.setFileName(fileName);
                fileQueryParam.setFileSize(fileSize);
                fileQueryParam.setFileType(fileType);
                fileQueryParam.setPId(dirId);
                fileQueryParam.setUserId(userId);
                fileQueryParam.setUserName(userId != null ? String.valueOf(userId) : "unknown");
                fileQueryParam.setFilePath(uploadContext.buildFilePath());
                com.alibaba.server.nio.repository.file.service.dto.FileDto fileDto = fileService
                        .createFile(fileQueryParam);
                // 保存数据库记录 ID，用于断连时删除
                if (fileDto != null && fileDto.getId() != null) {
                    uploadContext.setFileId(fileDto.getId());
                }
            } catch (Exception e) {
                log.warn("创建数据库记录失败（非致命）: {}", e.getMessage());
                // 失败时释放信号量
                uploadContext.releaseSemaphore(uploadSemaphore);
                throw e;
            }

            // 6. 打开文件通道
            try {
                uploadContext.openFileChannel();
            } catch (Exception e) {
                // 失败时释放信号量
                uploadContext.releaseSemaphore(uploadSemaphore);
                throw e;
            }

            // 7. 保存上下文
            uploadContextMap.put(uploadContext.getTaskId(), uploadContext);

            // 8. 发送 ACK 给客户端
            sendAckFrame(socketChannelContext, uploadContext.getTaskId(), "ready", null);

            // 9. 初始化速率限制器（从配置读取）
            long rateLimitBps = config.getPerConnectionRateBps();
            
            // 如果启用动态速率调整，根据当前并发数计算
            if (config.isEnableDynamicRateAdjustment()) {
                int currentUploads = uploadContextMap.size();
                rateLimitBps = config.calculateDynamicRate(currentUploads);
            }
            
            socketChannelContext.setRateLimiter(new TokenBucketRateLimiter(
                rateLimitBps, 
                rateLimitBps * config.getBucketCapacityMultiplier()
            ));
            
            int currentConcurrent = config.getMaxConcurrentUploads() - uploadSemaphore.availablePermits();
            log.info("文件上传通道元数据处理完成 - 任务ID: {}, 文件: {}, 通道: {}, 单连接速率: {} B/s, 当前并发: {}/{}",
                    uploadContext.getTaskId(),
                    uploadContext.getFileName(), 
                    socketChannelContext.getRemoteAddress(), 
                    rateLimitBps,
                    currentConcurrent,
                    config.getMaxConcurrentUploads());

        } catch (Exception e) {
            log.error("处理元数据帧失败", e);
            sendAckFrame(socketChannelContext, null, "error", e.getMessage());
        }
    }

    /**
     * 处理数据帧（DATA_FRAME）
     * 将文件字节数据写入本地文件
     */
    private void handleDataFrame(FileUploadFrame frame, SocketChannelContext socketChannelContext) {
        try {
            // 从帧数据中解析 taskId（假设数据格式：taskId + 实际数据）
            // 简化处理：这里假设每个连接只有一个活跃的上传任务
            FileUploadContext uploadContext = getActiveUploadContext(socketChannelContext);

            if (uploadContext == null) {
                log.error("未找到活跃的上传上下文，remoteAddress={}", socketChannelContext.getRemoteAddress());
                return;
            }

            // 写入文件数据
            byte[] fileData = frame.getData();
            if (fileData != null && fileData.length > 0) {
                uploadContext.writeData(fileData);

                // 可选：每写入一定量数据后记录进度
                if (uploadContext.getBytesWritten() % (1024 * 1024) == 0) { // 每 1MB 记录一次
                    log.debug("上传进度: taskId={}, progress={:.2f}%",
                            uploadContext.getTaskId(), uploadContext.getProgress());
                }
            }

        } catch (Exception e) {
            log.error("处理数据帧失败", e);
        }
    }

    /**
     * 处理结束帧（END_FRAME）
     * 关闭文件通道，更新数据库状态，通知客户端
     */
    private void handleEndFrame(FileUploadFrame frame,
            SocketChannelContext socketChannelContext,
            SimpleChannelContext simpleChannelContext) {
        try {
            // 解析结束帧数据
            String jsonData = frame.getDataAsString();
            JSONObject endData = JSON.parseObject(jsonData);
            String taskId = endData.getString("taskId");

            FileUploadContext uploadContext = uploadContextMap.get(taskId);
            if (uploadContext == null) {
                uploadContext = getActiveUploadContext(socketChannelContext);
            }

            if (uploadContext == null) {
                log.error("未找到上传上下文: taskId={}", taskId);
                sendAckFrame(socketChannelContext, taskId, "error", "任务不存在");
                return;
            }

            // 1. 关闭文件通道
            uploadContext.markCompleted();

            // 2. 更新数据库状态, 先不更新文件数据库状态
            /*
             * try {
             * FileService fileService =
             * BasicServer.classPathXmlApplicationContext.getBean(FileService.class);
             * fileService.update(uploadContext.getTaskId(), "COMPLETED"); // 可选
             * } catch (Exception e) {
             * log.warn("更新数据库状态失败（非致命）: {}", e.getMessage());
             * throw e;
             * }
             */

            // 3. 发送完成 ACK
            sendAckFrame(socketChannelContext, uploadContext.getTaskId(), "success", null);

            log.info("[ {} ] FileHeadDecodeHandler | --> 文件上传完成: taskId={}, fileName={}, size={}, 耗时={}ms",
                    LocalTime.formatDate(LocalDateTime.now()),
                    uploadContext.getTaskId(),
                    uploadContext.getFileName(),
                    uploadContext.getBytesWritten(),
                    System.currentTimeMillis() - uploadContext.getStartTime().atZone(java.time.ZoneId.systemDefault())
                            .toInstant().toEpochMilli());

            // 4. 释放并发许可
            uploadContext.releaseSemaphore(uploadSemaphore);

            // 5. 清理资源
            uploadContextMap.remove(uploadContext.getTaskId());
            parserMap.remove(socketChannelContext.getRemoteAddress());
            AbstractEventHandler.channelDataMap.remove(socketChannelContext.getRemoteAddress());

            // 6. 关闭通道
            NioServerContext.closedAndRelease(socketChannelContext.getSocketChannel());

            // 7. 终止后续 Handler
            simpleChannelContext.setNeedStop(Boolean.TRUE);

        } catch (Exception e) {
            log.error("处理结束帧失败", e);
        }
    }

    /**
     * 获取当前连接的活跃上传上下文
     */
    private FileUploadContext getActiveUploadContext(SocketChannelContext socketChannelContext) {
        String remoteAddress = socketChannelContext.getRemoteAddress();
        // 根据远程地址精确匹配上传上下文，确保数据写入正确的文件
        for (FileUploadContext ctx : uploadContextMap.values()) {
            // 同时检查状态和远程地址，避免多客户端并发上传时数据写入错误文件
            if (ctx.getStatus() == FileUploadContext.UploadStatus.UPLOADING
                    && remoteAddress != null
                    && remoteAddress.equals(ctx.getRemoteAddress())) {
                return ctx;
            }
        }
        return null;
    }

    /**
     * 发送 ACK 帧给客户端（使用 NIO 事件驱动的写操作）
     */
    private void sendAckFrame(SocketChannelContext socketChannelContext,
            String taskId, String status, String message) throws IOException {
        try {
            SocketChannel socketChannel = socketChannelContext.getSocketChannel();
            if (socketChannel == null || !socketChannel.isOpen()) {
                log.warn("通道已关闭，无法发送 ACK");
                return;
            }

            // 构建 ACK 响应 JSON
            JSONObject ackJson = new JSONObject();
            ackJson.put("taskId", taskId);
            ackJson.put("status", status);
            if (message != null) {
                ackJson.put("message", message);
            }

            byte[] ackData = ackJson.toJSONString().getBytes(StandardCharsets.UTF_8);

            // 构建 ACK 帧
            ByteBuffer buffer = ByteBuffer.allocate(FileUploadFrame.HEADER_LENGTH + ackData.length);
            buffer.put(FileUploadFrame.MAGIC); // 魔数 2字节
            buffer.put((byte) FrameType.ACK_FRAME.getCode()); // 帧类型 1字节
            buffer.put((byte) 0); // 标志位 1字节
            buffer.putInt(ackData.length); // 数据长度 4字节
            buffer.put(ackData); // 数据
            buffer.flip();

            // 使用 NIO 事件驱动的写操作（替代直接 write）
            com.alibaba.server.nio.handler.event.concret.WriteQueueHelper.submitWrite(socketChannelContext, buffer);

            log.debug("发送 ACK 帧: taskId={}, status={}", taskId, status);

        } catch (Exception e) {
            log.error("发送 ACK 帧失败", e);
            throw e;
        }
    }

    /**
     * 清理指定连接的资源（断连时调用）
     * 删除未完成上传的临时文件和数据库记录
     * 
     * @param remoteAddress 客户端远程地址
     */
    public static void cleanupConnection(String remoteAddress) {
        // 清理解析器
        parserMap.remove(remoteAddress);

        // 清理该连接相关的上传上下文
        uploadContextMap.entrySet().removeIf(entry -> {
            FileUploadContext ctx = entry.getValue();
            // 只清理属于该客户端且未完成的上传
            if (remoteAddress.equals(ctx.getRemoteAddress())
                    && ctx.getStatus() != FileUploadContext.UploadStatus.COMPLETED) {

                log.warn("清理未完成的文件上传: taskId={}, fileName={}, remoteAddress={}, bytesWritten={}/{}",
                        ctx.getTaskId(), ctx.getFileName(), remoteAddress, ctx.getBytesWritten(), ctx.getFileSize());

                // 1. 释放并发许可
                ctx.releaseSemaphore(uploadSemaphore);

                // 2. 标记失败（会删除临时文件）
                ctx.markFailed("客户端断开连接");

                // 3. 删除数据库记录
                if (ctx.getFileId() != null) {
                    try {
                        FileService fileService = NioServerContext.getFileService();
                        if (fileService != null) {
                            fileService.deleteFileById(ctx.getFileId());
                        }
                    } catch (Exception e) {
                        log.error("删除数据库记录失败: fileId={}", ctx.getFileId(), e);
                    }
                }
                return true; // 从 Map 中移除
            }
            return false;
        });
    }
}
