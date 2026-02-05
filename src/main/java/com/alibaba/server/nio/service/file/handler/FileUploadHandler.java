package com.alibaba.server.nio.service.file.handler;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.server.nio.core.server.BasicServer;
import com.alibaba.server.nio.core.server.NioServerContext;
import com.alibaba.server.nio.handler.event.AbstractEventHandler;
import com.alibaba.server.nio.handler.event.concret.WriteQueueHelper;
import com.alibaba.server.nio.handler.pipe.ChannelContext;
import com.alibaba.server.nio.handler.pipe.standard.SimpleChannelContext;
import com.alibaba.server.nio.model.ChannelEventModel;
import com.alibaba.server.nio.model.SocketChannelContext;
import com.alibaba.server.nio.model.TransportDataModel;
import com.alibaba.server.nio.model.file.FileUploadContext;
import com.alibaba.server.nio.model.file.FileUploadFrame;
import com.alibaba.server.nio.model.file.FileUploadFrame.FrameType;
import com.alibaba.server.nio.model.file.UploadCheckpoint;
import com.alibaba.server.nio.repository.file.service.FileService;
import com.alibaba.server.nio.repository.file.service.dto.FileDto;
import com.alibaba.server.nio.repository.file.service.param.FileQueryParam;
import com.alibaba.server.nio.repository.user.service.dto.UserDTO;
import com.alibaba.server.nio.service.api.AbstractChannelHandler;
import com.alibaba.server.nio.service.file.checkpoint.CheckpointManager;
import com.alibaba.server.nio.service.file.config.FileUploadConfig;
import com.alibaba.server.nio.service.file.parser.FrameParser;
import com.alibaba.server.nio.service.ratelimit.TokenBucketRateLimiter;
import com.alibaba.server.util.LocalTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

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
    private static Semaphore uploadSemaphore;
    /**
     * 上传上下文缓存: 一个taskId对应一个文件上传上下文对象
     */
    private static final ConcurrentHashMap<String, FileUploadContext> uploadContextMap = new ConcurrentHashMap<>();
    /**
     * 帧解析器缓存：一个remoteAddress对应一个帧解析器
     */
    private static final ConcurrentHashMap<String, FrameParser> parserMap = new ConcurrentHashMap<>();

    /**
     * 静态初始化块：初始化全局限流器和信号量
     */
    static {
        // 使用默认配置
        config = new FileUploadConfig();
        // 构建全局限流器
        globalRateLimiter = new TokenBucketRateLimiter(config.getGlobalRateBps(), config.getGlobalBucketCapacity());
        uploadSemaphore = new Semaphore(config.getMaxConcurrentUploads());
        log.info("文件上传控制器初始化完成 - 全局速率: {}, 单连接速率: {}, 最大并发: {}",
                formatRate(config.getGlobalRateBps()),
                formatRate(config.getPerConnectionRateBps()),
                config.getMaxConcurrentUploads());
    }

    /**
     * 格式化速率显示（自动选择单位：B/s, KB/s, MB/s）
     * 
     * @param bytesPerSecond 字节/秒
     * @return 格式化后的速率字符串
     */
    private static String formatRate(long bytesPerSecond) {
        if (bytesPerSecond < 1024) {
            return bytesPerSecond + " B/s";
        } else if (bytesPerSecond < 1024 * 1024) {
            return Math.round(bytesPerSecond / 1024.0) + " KB/s";
        } else {
            return Math.round(bytesPerSecond / (1024.0 * 1024.0)) + " MB/s";
        }
    }

    /**
     * 获取全局速率限制器
     * 供 ReadEventHandler 使用，用于实现全局带宽控制
     * 
     * @return 全局速率限制器实例
     */
    public static TokenBucketRateLimiter getGlobalRateLimiter() {
        return globalRateLimiter;
    }

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

        // log.debug("处理帧: type={}, dataLength={}", frame.getType(),
        // frame.getDataLength());

        switch (frame.getType()) {
            case RESUME_CHECK: // 如果当前文件上传时默认会发送为断点续传帧，因为不确定当前文件上传是全新上传还是断点续传
                handleResumeCheck(frame, socketChannelContext, simpleChannelContext);
                break;
            case META_FRAME: // 如果判定为文件上传为全新上传，则会以文件上传元数据帧开始
                handleMetaFrame(frame, socketChannelContext, simpleChannelContext);
                break;
            case DATA_FRAME: // 文件数据帧
                handleDataFrame(frame, socketChannelContext);
                break;
            case END_FRAME: // 文件结束帧
                handleEndFrame(frame, socketChannelContext, simpleChannelContext);
                break;
            default:
                log.warn("未知帧类型: {}", frame.getType());
                break;
        }
    }

    /**
     * 处理断点检查帧（RESUME_CHECK）
     * 检查文件是否存在断点，返回已上传大小
     */
    private void handleResumeCheck(FileUploadFrame frame,
            SocketChannelContext socketChannelContext,
            SimpleChannelContext simpleChannelContext) throws IOException {
        try {
            // 1. 解析 JSON 元数据
            String jsonData = frame.getDataAsString();
            JSONObject meta = JSON.parseObject(jsonData);
            String md5 = meta.getString("md5");
            String fileName = meta.getString("fileName");
            long fileSize = meta.getLongValue("fileSize");
            String fileType = meta.getString("fileType");
            Long dirId = meta.getLong("dirId");
            Long userId = meta.getLong("userId");

            log.info("[ {} ] FileUploadHandler | --> 接收到断点检查帧: md5={}, fileName={}, fileSize={}",
                    com.alibaba.server.util.LocalTime.formatDate(java.time.LocalDateTime.now()), md5, fileName,
                    fileSize);

            // 2. 检查是否有断点记录
            UploadCheckpoint checkpoint = CheckpointManager.getCheckpoint(md5);
            if (Objects.nonNull(checkpoint) && new File(checkpoint.getFilePath()).exists()) {
                // ==== 断点续传模式 ====
                long uploadedSize = checkpoint.getUploadedSize();

                // 使用通用方法创建上传上下文
                FileUploadContext uploadContext = createAndInitializeUploadContext(
                        meta, socketChannelContext, true, checkpoint);

                if (uploadContext == null) {
                    sendResumeAck(socketChannelContext, null, "error", 0, "服务器繁忙或目录错误");
                    return;
                }

                // 发送断点应答
                sendResumeAck(socketChannelContext, uploadContext.getTaskId(), "resume",
                        uploadedSize, "断点续传");

                log.info("断点续传 - taskId: {}, 文件: {}, 已上传: {} / {} ({:.2f}%), 剩余: {}",
                        uploadContext.getTaskId(),
                        fileName,
                        formatBytes(uploadedSize),
                        formatBytes(fileSize),
                        uploadContext.getProgress(),
                        formatBytes(fileSize - uploadedSize));

            } else {
                // ==== 全新上传模式 ====
                sendResumeAck(socketChannelContext, null, "new", 0, "全新上传，请发送META_FRAME");
                log.info("无断点记录，指示客户端全新上传: fileName={}", fileName);
            }

        } catch (Exception e) {
            log.error("处理断点检查帧失败", e);
            sendResumeAck(socketChannelContext, null, "error", 0, e.getMessage());
        }
    }

    /**
     * 创建并初始化上传上下文（通用方法）
     * 支持全新上传和断点续传两种模式
     * 
     * @param meta                 元数据JSON对象
     * @param socketChannelContext socket通道上下文
     * @param isResume             是否为断点续传模式
     * @param checkpoint           断点信息（续传模式时传入，全新上传时为null）
     * @return 创建的上传上下文，如果失败返回null
     */
    private FileUploadContext createAndInitializeUploadContext(
            JSONObject meta,
            SocketChannelContext socketChannelContext,
            boolean isResume,
            UploadCheckpoint checkpoint) throws IOException {

        // 1. 解析元数据
        String md5 = meta.getString("md5");
        String fileName = meta.getString("fileName");
        long fileSize = meta.getLongValue("fileSize");
        String fileType = meta.getString("fileType");
        Long dirId = meta.getLong("dirId");
        Integer userId = meta.getInteger("userId");
        String taskId = meta.getString("taskId");

        // 2. 校验目录（如果指定了dirId）
        FileService fileService = BasicServer.classPathXmlApplicationContext.getBean(FileService.class);
        String dirPath = null;
        Long fileId = null;
        if (dirId != null) {
            dirPath = fileService.validateDirectory(dirId);
            if (dirPath == null) {
                log.error("目录不存在或类型错误: dirId={}，上传文件时dirId必须是目录Id", dirId);
                return null;
            }
        }

        // 3. 创建数据库记录（仅全新上传需要）
        if (!isResume) {
            FileQueryParam fileQueryParam = new FileQueryParam();
            fileQueryParam.setParentId(dirId);
            fileQueryParam.setFileName(fileName);
            fileQueryParam.setFileSize(fileSize);
            fileQueryParam.setUserId(userId);
            fileQueryParam.setFilePath(dirPath + File.separator + taskId + "_" + fileName);
            fileQueryParam.setFileType(fileType);

            try {
                UserDTO userDTO = new UserDTO();
                userDTO.setId(Long.valueOf(String.valueOf(userId)));
                userDTO.setUserName("default");
                socketChannelContext.setUserDTO(userDTO);
                FileDto fileDto = fileService.createFile(fileQueryParam, socketChannelContext.getUserDTO());
                // 保存数据库记录 ID
                if (fileDto != null && fileDto.getId() != null) {
                    fileId = fileDto.getId();
                }
                log.debug("创建文件记录成功: fileId={}", fileId);
            } catch (Exception e) {
                log.error("创建文件记录失败", e);
                return null;
            }
        }

        // 4. 创建上传上下文
        FileUploadContext uploadContext = new FileUploadContext();
        uploadContext.setTaskId(taskId);
        uploadContext.setMd5(md5);
        uploadContext.setFileName(fileName);
        uploadContext.setFileSize(fileSize);
        uploadContext.setFileType(fileType);
        uploadContext.setRemoteAddress(socketChannelContext.getRemoteAddress());

        // 5. 设置断点续传相关字段
        if (isResume && checkpoint != null) {
            // 断点续传模式
            uploadContext.setStartOffset(checkpoint.getUploadedSize());
            uploadContext.setResume(true);
            uploadContext.setFilePath(checkpoint.getFilePath());
            uploadContext.setFileId(checkpoint.getFileId());
        } else {
            // 全新上传模式
            uploadContext.setStartOffset(0);
            uploadContext.setResume(false);
            uploadContext.setFileId(fileId);
        }

        // 6. 设置目录路径
        if (dirPath != null) {
            uploadContext.setBasePath(dirPath);
        }

        // 7. 获取并发许可
        if (!uploadSemaphore.tryAcquire()) {
            int currentUploads = config.getMaxConcurrentUploads() - uploadSemaphore.availablePermits();
            log.warn("上传并发数已达上限: 当前并发={}/{}", currentUploads, config.getMaxConcurrentUploads());

            // 删除刚创建的数据库记录（如果有）
            if (fileId != null) {
                try {
                    fileService.deleteFileById(fileId);
                } catch (Exception e) {
                    log.error("删除数据库记录失败", e);
                }
            }
            return null;
        }
        uploadContext.setSemaphoreAcquired(true);

        // 8. 打开文件通道
        try {
            uploadContext.openFileChannel();
        } catch (IOException e) {
            log.error("打开文件通道失败", e);
            uploadContext.releaseSemaphore(uploadSemaphore);

            // 删除数据库记录
            if (uploadContext.getFileId() != null) {
                try {
                    fileService.deleteFileById(uploadContext.getFileId());
                } catch (Exception ex) {
                    log.error("删除数据库记录失败", ex);
                }
            }
            throw e;
        }

        // 9. 保存上下文到Map
        uploadContextMap.put(uploadContext.getTaskId(), uploadContext);

        // 10. 设置限流器
        long rateLimitBps = config.getPerConnectionRateBps();
        if (config.isEnableDynamicRateAdjustment()) {
            int currentUploads = uploadContextMap.size();
            rateLimitBps = config.calculateDynamicRate(currentUploads);
        }
        socketChannelContext.setRateLimiter(new TokenBucketRateLimiter(
                rateLimitBps,
                rateLimitBps * config.getBucketCapacityMultiplier()));

        log.info("上传上下文创建成功 - taskId: {}, 文件: {}, 模式: {}, 起始偏移: {}",
                uploadContext.getTaskId(), fileName,
                isResume ? "断点续传" : "全新上传",
                uploadContext.getStartOffset());

        return uploadContext;
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
            Integer userId = meta.getInteger("userId");
            String taskId = meta.getString("taskId");

            log.info(
                    "[ {} ] FileHeadDecodeHandler | --> 接收到元数据帧: fileName={}, fileSize={}, fileType={}, dirId={}, userId={}",
                    LocalTime.formatDate(LocalDateTime.now()), fileName, fileSize, fileType, dirId, userId);

            // 2. 使用通用方法创建上传上下文（全新上传模式，isResume=false）
            FileUploadContext uploadContext = createAndInitializeUploadContext(
                    meta, socketChannelContext, false, null);

            if (uploadContext == null) {
                sendAckFrame(socketChannelContext, null, "error", "服务器繁忙或目录错误");
                return;
            }

            // 3. 发送 ACK 给客户端
            sendAckFrame(socketChannelContext, uploadContext.getTaskId(), "ready", null);

            log.info("文件上传通道元数据处理完成 - 任务ID: {}, 文件: {}, 通道: {}, 单连接速率: {} B/s, 当前并发: {}/{}",
                    uploadContext.getTaskId(),
                    uploadContext.getFileName(),
                    socketChannelContext.getRemoteAddress(),
                    config.getPerConnectionRateBps(),
                    config.getMaxConcurrentUploads() - uploadSemaphore.availablePermits(),
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

                // 更新速率统计
                uploadContext.updateSpeed();

                // 每接收到数据就输出进度（使用 \r 实现单行滚动更新）
                // 注：在生产环境中，可以调整输出频率以减少日志量
                long bytesWritten = uploadContext.getBytesWritten();
                long fileSize = uploadContext.getFileSize();
                double progress = uploadContext.getProgress();
                String speed = uploadContext.getFormattedSpeed();

                // 使用 System.out.print 而不是 log，实现单行滚动
                // 格式：[文件名] 进度: XX.XX% (已上传/总大小) 速率: XX.XX MB/s
                String progressBar = generateProgressBar(progress, 30);
                /*
                 * System.out.print(String.format(
                 * "\r[%s] %s %.2f%% (%s/%s) 速率: %s",
                 * uploadContext.getFileName(),
                 * progressBar,
                 * progress,
                 * formatBytes(bytesWritten),
                 * formatBytes(fileSize),
                 * speed
                 * ));
                 * System.out.flush();
                 */

                // 也保留一个详细日志用于调试（降低频率）
                // 也保留一个详细日志用于调试（降低频率）
                if (bytesWritten % (10 * 1024 * 1024) == 0 || uploadContext.isComplete()) { // 每 10MB 或完成时记录
                    String progressStr = String.format("%.1f%%", progress);
                    String sizePair = formatSizePair(bytesWritten, fileSize);
                    log.info("上传进度 - taskId: {}, 文件: {}, 进度: {}, 已上传: {}, 速率: {}",
                            uploadContext.getTaskId(),
                            uploadContext.getFileName(),
                            progressStr,
                            sizePair,
                            speed);
                }
            }

        } catch (Exception e) {
            log.error("处理数据帧失败", e);
        }
    }

    /**
     * 生成进度条
     * 
     * @param progress  进度百分比 (0-100)
     * @param barLength 进度条长度
     * @return 进度条字符串，例如：[=========> ]
     */
    private String generateProgressBar(double progress, int barLength) {
        int filledLength = (int) (barLength * progress / 100.0);
        StringBuilder bar = new StringBuilder("[");

        for (int i = 0; i < barLength; i++) {
            if (i < filledLength - 1) {
                bar.append("=");
            } else if (i == filledLength - 1) {
                bar.append(">");
            } else {
                bar.append(" ");
            }
        }

        bar.append("]");
        return bar.toString();
    }

    /**
     * 格式化字节大小
     * 
     * @param bytes 字节数
     *              /**
     *              格式化文件大小对（统一单位，保留一位小数）
     *              例如：[10.5 MB / 20.0 MB]
     */
    private String formatSizePair(long written, long total) {
        if (total < 1024) {
            return String.format("[%d B / %d B]", written, total);
        } else if (total < 1024 * 1024) {
            return String.format("[%.1f KB / %.1f KB]", written / 1024.0, total / 1024.0);
        } else if (total < 1024 * 1024 * 1024) {
            return String.format("[%.1f MB / %.1f MB]", written / 1024.0 / 1024.0, total / 1024.0 / 1024.0);
        } else {
            return String.format("[%.1f GB / %.1f GB]", written / 1024.0 / 1024.0 / 1024.0,
                    total / 1024.0 / 1024.0 / 1024.0);
        }
    }

    /**
     * 格式化字节大小
     * 
     * @param bytes 字节数
     * @return 格式化后的字符串，例如：1.23 MB
     */
    private String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.2f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", bytes / 1024.0 / 1024.0);
        } else {
            return String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0);
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
            // 3.5 删除断点记录（上传完成）
            if (uploadContext.getMd5() != null) {
                CheckpointManager.removeCheckpoint(uploadContext.getMd5());
                log.debug("上传完成，删除断点记录: MD5={}", uploadContext.getMd5());
            }
            // 4. 释放并发许可
            uploadContext.releaseSemaphore(uploadSemaphore);
            // 5. 清理资源（包含本次文件上传任务和解析器）
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
     * 发送断点应答帧（RESUME_ACK）
     */
    private void sendResumeAck(SocketChannelContext ctx, String taskId,
            String status, long uploadedSize, String message) throws IOException {
        JSONObject ackJson = new JSONObject();
        ackJson.put("taskId", taskId);
        ackJson.put("status", status);
        ackJson.put("uploadedSize", uploadedSize);
        ackJson.put("message", message);

        byte[] ackData = ackJson.toJSONString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(FileUploadFrame.HEADER_LENGTH + ackData.length);
        buffer.put(FileUploadFrame.MAGIC);
        buffer.put((byte) FileUploadFrame.FrameType.RESUME_ACK.getCode());
        buffer.put((byte) 0);
        buffer.putInt(ackData.length);
        buffer.put(ackData);
        buffer.flip();

        WriteQueueHelper.submitWrite(ctx, buffer);
        log.debug("发送断点应答: status={}, uploadedSize={}, taskId={}", status, uploadedSize, taskId);
    }

    /**
     * 发送 ACK 帧给客户端（使用 NIO 事件驱动的写操作）
     */
    private void sendAckFrame(SocketChannelContext socketChannelContext,
            String taskId,
            String status,
            String message) throws IOException {
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

                log.warn("连接断开，保存断点信息: taskId={}, fileName={}, remoteAddress={}, uploaded={}/{}",
                        ctx.getTaskId(), ctx.getFileName(), remoteAddress, ctx.getBytesWritten(), ctx.getFileSize());

                // 1. 保存断点而非删除文件（支持断点续传）
                if (ctx.getMd5() != null && ctx.getBytesWritten() > 0) {
                    UploadCheckpoint checkpoint = new UploadCheckpoint();
                    checkpoint.setMd5(ctx.getMd5());
                    checkpoint.setFileName(ctx.getFileName());
                    checkpoint.setFileSize(ctx.getFileSize());
                    checkpoint.setUploadedSize(ctx.getBytesWritten());
                    checkpoint.setFilePath(ctx.getFilePath());
                    checkpoint.setFileId(ctx.getFileId());
                    checkpoint.setUserId(ctx.getFileId() != null ? ctx.getFileId() : null); // 简化处理
                    checkpoint.setUpdateTime(java.time.LocalDateTime.now());
                    checkpoint.setCreateTime(ctx.getStartTime());

                    CheckpointManager.saveCheckpoint(checkpoint);
                    log.info("已保存断点，支持续传: MD5={}, 已上传={} ({:.2f}%)",
                            ctx.getMd5(), ctx.getBytesWritten(), ctx.getProgress());
                } else {
                    // 无 MD5 或未上传任何数据，删除文件
                    ctx.markFailed("客户端断开连接");
                    log.info("无断点信息，删除临时文件: fileName={}", ctx.getFileName());
                }

                // 2. 释放并发许可
                ctx.releaseSemaphore(uploadSemaphore);

                // 3. 关闭文件通道（但不删除文件）
                ctx.closeFileChannel();

                // 4. 删除数据库记录（仅在无断点时）
                if (ctx.getMd5() == null && ctx.getFileId() != null) {
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
