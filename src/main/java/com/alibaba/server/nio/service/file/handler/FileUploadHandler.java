package com.alibaba.server.nio.service.file.handler;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.server.common.FileTaskStatusEnum;
import com.alibaba.server.common.YesOrNoEnum;
import com.alibaba.server.nio.core.server.NioServerContext;
import com.alibaba.server.nio.handler.event.AbstractEventHandler;
import com.alibaba.server.nio.handler.event.concret.WriteQueueHelper;
import com.alibaba.server.nio.handler.worker.WorkerThreadPool;
import com.alibaba.server.nio.handler.pipe.ChannelContext;
import com.alibaba.server.nio.handler.pipe.standard.SimpleChannelContext;
import com.alibaba.server.nio.model.ChannelEventModel;
import com.alibaba.server.nio.model.SocketChannelContext;
import com.alibaba.server.nio.model.TransportDataModel;
import com.alibaba.server.nio.model.file.FileUploadContext;
import com.alibaba.server.nio.model.file.FileUploadFrame;
import com.alibaba.server.nio.model.file.FileUploadFrame.FrameType;
import com.alibaba.server.nio.model.file.UploadCheckpoint;
import com.alibaba.server.nio.model.file.request.FileUploadRequest;
import com.alibaba.server.nio.repository.file.repository.dataobject.FileDo;
import com.alibaba.server.nio.repository.file.service.FileService;
import com.alibaba.server.nio.repository.file.service.dto.FileDto;
import com.alibaba.server.nio.repository.file.service.dto.FileTaskDto;
import com.alibaba.server.nio.repository.user.service.dto.UserDTO;
import com.alibaba.server.nio.service.api.AbstractChannelHandler;
import com.alibaba.server.nio.service.file.adaptive.AdaptiveThrottlePolicy;
import com.alibaba.server.nio.service.file.adaptive.ResourcePressureLevel;
import com.alibaba.server.nio.service.file.adaptive.ServerResourceMonitor;
import com.alibaba.server.nio.service.file.adaptive.UploadBackpressureController;
import com.alibaba.server.nio.service.file.adaptive.UploadBackpressureDecision;
import com.alibaba.server.nio.service.file.checkpoint.CheckpointManager;
import com.alibaba.server.nio.service.file.config.FileUploadConfig;
import com.alibaba.server.nio.service.file.parser.FrameUploadParser;
import com.alibaba.server.nio.service.file.security.TransferTokenFactory;
import com.alibaba.server.nio.service.file.security.TransferTokenService;
import com.alibaba.server.nio.service.ratelimit.TokenBucketRateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.springframework.util.CollectionUtils;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Date;
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
    private static final String UPLOAD_PURPOSE_CHAT_ATTACHMENT = "CHAT_ATTACHMENT";
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

    private static UploadBackpressureController uploadBackpressureController;
    /**
     * 上传上下文缓存: 一个taskId对应一个文件上传上下文对象
     */
    private static final ConcurrentHashMap<String, FileUploadContext> uploadContextMap = new ConcurrentHashMap<>();

    /** 活跃上传任务对应的通道，用于并发变化时重新分配公平带宽。 */
    private static final ConcurrentHashMap<String, SocketChannelContext> uploadChannelContextMap =
            new ConcurrentHashMap<>();
    /**
     * 帧解析器缓存：一个remoteAddress对应一个帧解析器
     */
    private static final ConcurrentHashMap<String, FrameUploadParser> parserMap = new ConcurrentHashMap<>();

    /**
     * 静态初始化块：初始化全局限流器和信号量
     */
    static {
        // 使用默认配置
        config = new FileUploadConfig();
        // 构建全局限流器
        globalRateLimiter = new TokenBucketRateLimiter(config.getGlobalRateBps(), config.getGlobalBucketCapacity());
        uploadSemaphore = new Semaphore(config.getMaxConcurrentUploads());
        uploadBackpressureController = new UploadBackpressureController(config);
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
        FrameUploadParser parser = getOrCreateParser(socketChannelContext);
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
    private FrameUploadParser getOrCreateParser(SocketChannelContext socketChannelContext) {
        String remoteAddress = socketChannelContext.getRemoteAddress();
        return parserMap.computeIfAbsent(remoteAddress, k -> {
            log.debug("为通道 {} 创建新的 FileUploadFrameParser", remoteAddress);
            return new FrameUploadParser();
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
        String requestTaskId = null;
        try {
            // 1. 解析 JSON 元数据
            String jsonData = frame.getDataAsString();
            FileUploadRequest fileUploadRequest = JSONObject.parseObject(jsonData, FileUploadRequest.class);
            if (Objects.isNull(fileUploadRequest)) {
                sendResumeAck(socketChannelContext, null, requestTaskId, "error", 0, "服务器繁忙或目录错误");
                return;
            }
            requestTaskId = fileUploadRequest.getTaskId();
            applyAuthenticatedIdentity(fileUploadRequest);
            log.info("=> 接收到断点检查帧: 入参信息={}", JSON.toJSONString(fileUploadRequest));

            String targetDirectory = resolveUploadDirectory(fileUploadRequest);
            String targetFilePath = targetDirectory + File.separator
                    + fileUploadRequest.getTaskId() + "_" + fileUploadRequest.getFileName();

            // 2. 检查是否有断点记录
            UploadCheckpoint checkpoint = CheckpointManager.getCheckpoint(
                    fileUploadRequest.getMd5(), fileUploadRequest.getUserId());
            if (checkpoint != null && !targetFilePath.equals(checkpoint.getFilePath())) {
                checkpoint = null;
            }
            // 2.1 如果内存中没有，尝试从数据库查找 PAUSED 状态的任务，如果内存有，说明服务端没宕机还保留数据，此时直接按照内存中的进行使用
            if (checkpoint == null) {
                FileTaskDto pausedTask = fileTaskService.findPausedTask(
                        fileUploadRequest.getMd5(), fileUploadRequest.getUserId(),
                        fileUploadRequest.getDirId(), targetFilePath);
                if (pausedTask != null) {
                    // 构造 Checkpoint 对象
                    checkpoint = new UploadCheckpoint();
                    checkpoint.setMd5(pausedTask.getMd5());
                    checkpoint.setFileName(pausedTask.getFileName());
                    checkpoint.setFileSize(pausedTask.getFileSize());
                    checkpoint.setUploadedSize(pausedTask.getCurrentOffset());
                    checkpoint.setFilePath(pausedTask.getFilePath());
                    checkpoint.setFileTaskId(pausedTask.getId());
                    checkpoint.setUserId(pausedTask.getUserId());
                    checkpoint.setUserName(pausedTask.getUserName());
                    // 假设 Task ID 为空或需要重新生成，这里暂时复用请求的 TaskId 或不设置，客户端请求时会带有taskId的，因为客户端本地
                    // 也记录了可能由于应用重启或是崩溃时的未完成的文件传输元数据
                    checkpoint.setRequestTaskId(fileUploadRequest.getTaskId());
                    log.info("从数据库恢复断点信息: taskId={}, offset={}", pausedTask.getId(), pausedTask.getCurrentOffset());
                }
            }
            if (checkpoint == null) {
                FileTaskDto successfulTask = fileTaskService.findSuccessfulTask(
                        fileUploadRequest.getMd5(), fileUploadRequest.getUserId(),
                        fileUploadRequest.getDirId(), targetFilePath);
                if (successfulTask != null
                        && StringUtils.isNotBlank(successfulTask.getFilePath())
                        && Files.isRegularFile(Paths.get(successfulTask.getFilePath()))
                        && Files.size(Paths.get(successfulTask.getFilePath())) == successfulTask.getFileSize()) {
                    FileDo completedFile = fileService.findFileByPath(
                            successfulTask.getFilePath(), fileUploadRequest.getUserId());
                    if (completedFile != null) {
                        FileUploadContext completedContext = new FileUploadContext();
                        completedContext.setRequestTaskId(fileUploadRequest.getTaskId());
                        completedContext.setFileId(completedFile.getId());
                        completedContext.setUserId(fileUploadRequest.getUserId());
                        sendResumeAck(socketChannelContext, completedContext,
                                "complete", successfulTask.getFileSize(), "上传已完成");
                        return;
                    }
                }
            }
            // 2.2 断点续传帧查到了当前上传任务的断点数据，则按照断点进行恢复传输
            if (Objects.nonNull(checkpoint) && new File(checkpoint.getFilePath()).exists()) {
                // ==== 断点续传模式 ====
                // 使用通用方法创建上传上下文（内部 openFileChannel 会以磁盘真实大小确定实际续传偏移）
                FileUploadContext uploadContext = createAndInitializeUploadContext(fileUploadRequest,
                        socketChannelContext, true, checkpoint);
                if (uploadContext == null) {
                    sendResumeAck(socketChannelContext, null, requestTaskId, "error", 0, "服务器繁忙或目录错误");
                    return;
                }
                // *** 关键：以服务端确定的实际磁盘偏移为准发给客户端 ***
                // 不能直接用 checkpoint.getUploadedSize()，因为磁盘实际大小可能与 checkpoint 有偏差
                // 客户端必须按照这个值来寻址源文件，才能确保服务端文件通道位置与客户端发送起点完全一致
                long actualOffset = uploadContext.getActualResumeOffset();
                sendResumeAck(socketChannelContext, uploadContext, "resume", actualOffset, "断点续传");
                log.info(
                        "断点续传 RESUME_ACK 已发送 - requestTaskId: {}, 文件: {}, 服务端实际续传偏移: {} bytes / 总大小: {} bytes ({:.2f}%)",
                        uploadContext.getRequestTaskId(),
                        fileUploadRequest.getFileName(),
                        actualOffset,
                        fileUploadRequest.getFileSize(),
                        fileUploadRequest.getFileSize() > 0 ? actualOffset * 100.0 / fileUploadRequest.getFileSize()
                                : 0.0);
            } else {
                // ==== 全新上传模式 ====
                FileUploadContext uploadContext = new FileUploadContext();
                uploadContext.setRequestTaskId(fileUploadRequest.getTaskId());
                sendResumeAck(socketChannelContext, uploadContext, "new", 0, "全新上传，请发送META_FRAME");
                log.info("无断点记录，指示客户端全新上传: fileName={}", fileUploadRequest.getFileName());
            }
        } catch (Exception e) {
            log.error("处理断点检查帧失败, error={}", ExceptionUtils.getStackTrace(e));
            sendResumeAck(socketChannelContext, null, requestTaskId, "error", 0, e.getMessage());
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
            FileUploadRequest request,
            SocketChannelContext socketChannelContext,
            boolean isResume,
            UploadCheckpoint checkpoint) throws IOException {
        // 1. 文件上传校验所属目录（如果指定了dirId）
        String dirPath = null;
        Long fileTaskId = null;
        dirPath = resolveUploadDirectory(request);
        // 3. 创建数据库记录（仅全新上传需要）
        if (!isResume) {
            FileTaskDto fileTaskDto = new FileTaskDto();
            fileTaskDto.setParentId(request.getDirId());
            fileTaskDto.setFileName(request.getFileName());
            fileTaskDto.setFilePath(dirPath + File.separator + request.getTaskId() + "_" + request.getFileName());
            fileTaskDto.setFileType(request.getFileType());
            fileTaskDto.setFileSize(request.getFileSize());
            fileTaskDto.setUserId(request.getUserId());
            fileTaskDto.setUserName(request.getUserName());
            fileTaskDto.setParentId(request.getDirId());
            // 默认为待上传状态
            fileTaskDto.setStatus(FileTaskStatusEnum.WAIT_UPLOAD.getCode());
            fileTaskDto.setMd5(request.getMd5());
            fileTaskDto.setCurrentOffset(0L);
            fileTaskDto.setLastActiveTime(new Date());

            try {
                UserDTO userDTO = new UserDTO();
                userDTO.setId(Long.valueOf(String.valueOf(request.getUserId())));
                userDTO.setUserName(request.getUserName());
                socketChannelContext.setUserDTO(userDTO);
                fileTaskDto = fileTaskService.create(fileTaskDto);
                // 保存数据库记录 ID
                if (fileTaskDto != null && fileTaskDto.getId() != null) {
                    fileTaskId = fileTaskDto.getId();
                }
                log.debug("创建文件传输记录成功: fileTaskId={}", fileTaskId);
            } catch (Exception e) {
                log.error("创建文件传输记录失败", e);
                return null;
            }
        }

        // 4. 创建上传上下文（断点续传时数据来自断点对象，如果是全新上传时来自请求）
        FileUploadContext uploadContext = new FileUploadContext();
        if (isResume && checkpoint != null) {
            // 断点续传模式, 从断点续传对象中恢复元数据
            uploadContext.setMd5(checkpoint.getMd5());
            uploadContext.setFileName(checkpoint.getFileName());
            uploadContext.setFileSize(checkpoint.getFileSize());
            uploadContext.setFileType(request.getFileType());
            uploadContext.setUserId(checkpoint.getUserId());
            uploadContext.setUserName(checkpoint.getUserName());
            uploadContext.setRemoteAddress(socketChannelContext.getRemoteAddress());
            uploadContext.setStartOffset(checkpoint.getUploadedSize());
            uploadContext.setResume(true);
            uploadContext.setFilePath(checkpoint.getFilePath());
            // 传输任务关联的标识元数据信息从断点对象中获取
            uploadContext.setRequestTaskId(checkpoint.getRequestTaskId());
            uploadContext.setFileId(checkpoint.getFileId());
            uploadContext.setFileTaskId(checkpoint.getFileTaskId());
        } else {
            // 全新上传模式，使用全新请求对象构建上下文
            uploadContext.setRequestTaskId(request.getTaskId());
            uploadContext.setMd5(request.getMd5());
            uploadContext.setFileName(request.getFileName());
            uploadContext.setFileSize(request.getFileSize());
            uploadContext.setFileType(request.getFileType());
            uploadContext.setUserId(request.getUserId());
            uploadContext.setUserName(request.getUserName());
            uploadContext.setRemoteAddress(socketChannelContext.getRemoteAddress());
            uploadContext.setStartOffset(0);
            uploadContext.setResume(false);
            // 传输任务关联的标识元数据信息从入参对象中获取，全新上传无断点对象
            uploadContext.setRequestTaskId(request.getTaskId());
            uploadContext.setFileId(null);
            uploadContext.setFileTaskId(fileTaskId);
        }
        // 6. 设置目录路径
        if (dirPath != null) {
            uploadContext.setBasePath(dirPath);
        }
        // 7. 获取并发许可
        // [修改] 自适应并发上限检查：资源压力高时提前拒绝，保护 CPU/磁盘/内存
        int adaptiveMax = AdaptiveThrottlePolicy.uploadMaxConcurrent();
        int currentUploads = config.getMaxConcurrentUploads() - uploadSemaphore.availablePermits();
        if (currentUploads >= adaptiveMax) {
            ResourcePressureLevel pressureLevel = ServerResourceMonitor.getInstance().getCurrentLevel();
            log.warn("上传并发已达自适应上限: current={}/{}, 压力等级={}", currentUploads, adaptiveMax, pressureLevel);
            if (Objects.nonNull(fileTaskId)) {
                try {
                    FileTaskDto fileTaskDto = new FileTaskDto();
                    fileTaskDto.setId(fileTaskId);
                    fileTaskDto.setDel(YesOrNoEnum.Y.name());
                    fileTaskDto.setGmtModified(new Date());
                    fileTaskService.update(fileTaskDto);
                } catch (Exception e) {
                    log.error("自适应拒绝时删除数据库记录失败", e);
                }
            }
            return null;
        }
        if (!uploadSemaphore.tryAcquire()) {
            currentUploads = config.getMaxConcurrentUploads() - uploadSemaphore.availablePermits();
            log.warn("上传并发数已达配置上限: 当前并发={}/{}", currentUploads, config.getMaxConcurrentUploads());
            // 删除刚创建的数据库记录（如果有）
            if (Objects.nonNull(fileTaskId)) {
                try {
                    FileTaskDto fileTaskDto = new FileTaskDto();
                    fileTaskDto.setId(fileTaskId);
                    fileTaskDto.setDel(YesOrNoEnum.Y.name());
                    fileTaskDto.setGmtModified(new Date());
                    fileTaskService.update(fileTaskDto);
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
            if (uploadContext.getFileTaskId() != null) {
                try {
                    FileTaskDto fileTaskDto = new FileTaskDto();
                    fileTaskDto.setId(uploadContext.getFileTaskId());
                    fileTaskDto.setDel(YesOrNoEnum.Y.name());
                    fileTaskDto.setGmtModified(new Date());
                    fileTaskService.update(fileTaskDto);
                } catch (Exception ex) {
                    log.error("删除数据库记录失败", ex);
                }
            }
            throw e;
        }

        // 9. 保存上下文到Map(使用的是客户端生成的taskId)
        uploadContextMap.put(uploadContext.getRequestTaskId(), uploadContext);
        uploadChannelContextMap.put(uploadContext.getRequestTaskId(), socketChannelContext);
        // 10. 每次活跃连接数变化都重新计算所有上传连接的公平预算。
        rebalanceUploadRateLimiters();

        // 将成功创建或恢复的任务 ID 绑定到当前的信道上下文
        // 防止后续 DATA_FRAME 纯靠 remoteAddress 匹配失效
        socketChannelContext.putAttribute("currentUploadTaskId", uploadContext.getRequestTaskId());

        log.info("上传上下文创建成功 - taskId: {}, 文件: {}, 模式: {}, 起始偏移: {}",
                uploadContext.getRequestTaskId(), uploadContext.getFileName(),
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
        String requestTaskId = null;
        try {
            // 1. 解析 JSON 元数据
            String jsonData = frame.getDataAsString();
            FileUploadRequest fileUploadRequest = JSONObject.parseObject(jsonData, FileUploadRequest.class);
            if (fileUploadRequest != null) {
                requestTaskId = fileUploadRequest.getTaskId();
            }
            applyAuthenticatedIdentity(fileUploadRequest);
            log.info("=> 文件上传接收到元数据帧: 入参数据={}", JSON.toJSONString(fileUploadRequest));
            // 2. 使用通用方法创建上传上下文（全新上传模式，isResume=false）
            FileUploadContext uploadContext = createAndInitializeUploadContext(fileUploadRequest, socketChannelContext,
                    false, null);
            if (Objects.isNull(uploadContext)) {
                sendAckFrame(socketChannelContext, null, requestTaskId, "error", "服务器繁忙或目录错误");
                return;
            }
            // 3. 发送 ACK 给客户t
            sendAckFrame(socketChannelContext, uploadContext, "ready", null);
            log.info("文件上传通道元数据处理完成 - 任务ID: {}, 文件: {}, 通道: {}, 单连接速率: {} B/s, 当前并发: {}/{}",
                    uploadContext.getRequestTaskId(),
                    uploadContext.getFileName(),
                    socketChannelContext.getRemoteAddress(),
                    config.getPerConnectionRateBps(),
                    config.getMaxConcurrentUploads() - uploadSemaphore.availablePermits(),
                    config.getMaxConcurrentUploads());
        } catch (Exception e) {
            log.error("处理元数据帧失败, error={}", ExceptionUtils.getStackTrace(e));
            sendAckFrame(socketChannelContext, null, requestTaskId, "error", e.getMessage());
        }
    }

    /**
     * 处理数据帧（DATA_FRAME）
     * 将文件字节数据写入本地文件
     */
    private void handleDataFrame(FileUploadFrame frame, SocketChannelContext socketChannelContext) {
        FileUploadContext uploadContext = null;
        try {
            // 从帧数据中解析 taskId（假设数据格式：taskId + 实际数据）
            // 简化处理：这里假设每个连接只有一个活跃的上传任务
            uploadContext = getActiveUploadContext(socketChannelContext);
            if (Objects.isNull(uploadContext)) {
                log.error("未找到活跃的上传上下文，remoteAddress={}", socketChannelContext.getRemoteAddress());
                if (frame.needAck()) {
                    sendAckFrame(socketChannelContext, null, null, "error", "上传任务上下文不存在", null);
                }
                return;
            }

            // 写入文件数据
            byte[] fileData = UploadDataFramePayloadDecoder.decode(frame, uploadContext.getBytesWritten());
            if (fileData != null && fileData.length > 0) {
                long writeStartedNanos = System.nanoTime();
                int writtenBytes = uploadContext.writeData(fileData);
                uploadContext.recordWindowWrite(writtenBytes, System.nanoTime() - writeStartedNanos);
                // 更新速率统计
                uploadContext.updateSpeed();

                // 定时/定量持久化进度
                long now = System.currentTimeMillis();
                if (now - uploadContext.getLastSavedTime() > 60000 ||
                        uploadContext.getBytesWritten() - uploadContext.getLastSavedOffset() > 60 * 1024 * 1024) {
                    if (uploadContext.getFileTaskId() != null) {
                        try {
                            fileTaskService.updateProgress(uploadContext.getFileTaskId(),
                                    uploadContext.getBytesWritten());
                            uploadContext.setLastSavedTime(now);
                            uploadContext.setLastSavedOffset(uploadContext.getBytesWritten());
                        } catch (Exception e) {
                            log.error("持久化进度失败: taskId={}", uploadContext.getFileTaskId(), e);
                        }
                    }
                }

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
                            uploadContext.getRequestTaskId(),
                            uploadContext.getFileName(),
                            progressStr,
                            sizePair,
                            speed);
                }
            }

            if (frame.needAck()) {
                UploadBackpressureDecision decision = createBackpressureDecision(
                        socketChannelContext, uploadContext);
                sendAckFrame(socketChannelContext, uploadContext, null, "progress", null,
                        uploadContext.getBytesWritten(), decision);
                uploadContext.resetWindowMetrics();
            }

        } catch (Exception e) {
            log.error("处理数据帧失败, error={}", ExceptionUtils.getStackTrace(e));
            if (frame.needAck()) {
                try {
                    sendAckFrame(socketChannelContext, uploadContext, null, "error", e.getMessage(), null);
                } catch (IOException ackError) {
                    log.error("发送上传数据帧错误应答失败", ackError);
                }
            }
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
            FileUploadRequest fileUploadRequest = JSONObject.parseObject(jsonData, FileUploadRequest.class);
            JSONObject endData = JSON.parseObject(jsonData);
            String taskId = endData.getString("taskId");
            Long fileId = endData.getLong("fileId");
            FileUploadContext uploadContext = uploadContextMap.get(fileUploadRequest.getTaskId());
            if (uploadContext == null) {
                uploadContext = getActiveUploadContext(socketChannelContext);
                if (Objects.nonNull(uploadContext)) {
                    // 文件传输上下文不为空设置元数据
                    uploadContext.setRequestTaskId(taskId);
                }
            }
            if (uploadContext == null) {
                log.error("未找到上传上下文: taskId={}, fileId={}", taskId, fileId);
                sendAckFrame(socketChannelContext, uploadContext, "error", "任务不存在");
                this.realeaseResource(uploadContext, socketChannelContext);
                return;
            }

            // 完成前先刷盘关闭，大小和 MD5 校验交给专用线程池，避免阻塞通用文件 Worker。
            uploadContext.markFinalizing();
            simpleChannelContext.setNeedStop(Boolean.TRUE);
            final FileUploadContext finalUploadContext = uploadContext;
            UploadFinalizeExecutor.submit(() -> finalizeUpload(
                    finalUploadContext, socketChannelContext, simpleChannelContext));
        } catch (Exception e) {
            log.error("处理结束帧失败", e);
        }
    }

    private void finalizeUpload(FileUploadContext uploadContext,
            SocketChannelContext socketChannelContext,
            SimpleChannelContext simpleChannelContext) {
        try {
            UploadIntegrityVerifier.VerificationResult verificationResult;
            try {
                verificationResult = UploadIntegrityVerifier.verify(
                        Paths.get(uploadContext.getFilePath()),
                        uploadContext.getFileSize(),
                        uploadContext.getMd5());
            } catch (IOException verifyError) {
                log.error("读取上传文件执行完整性校验失败: taskId={}, filePath={}",
                        uploadContext.getRequestTaskId(), uploadContext.getFilePath(), verifyError);
                failIntegrityCheck(uploadContext, socketChannelContext, simpleChannelContext,
                        "上传文件完整性校验失败");
                return;
            }
            if (!verificationResult.isValid()) {
                log.warn("上传文件完整性校验未通过: taskId={}, fileName={}, reason={}",
                        uploadContext.getRequestTaskId(), uploadContext.getFileName(),
                        verificationResult.getMessage());
                failIntegrityCheck(uploadContext, socketChannelContext, simpleChannelContext,
                        verificationResult.getMessage());
                return;
            }
            uploadContext.markCompleted();
            // 更新数据库文件传输任务状态, 文件主表插入数据
            FileTaskDto fileTaskDto = new FileTaskDto();
            fileTaskDto.setId(uploadContext.getFileTaskId());
            fileTaskDto.setStatus(FileTaskStatusEnum.UPLOAD_SUCCESS.getCode());
            fileTaskDto.setCurrentOffset(uploadContext.getFileSize());
            fileTaskDto.setGmtModified(new Date());
            fileTaskService.update(fileTaskDto);
            fileTaskDto = fileTaskService.getById(fileTaskDto.getId());
            FileDo fileDo = fileService.createByTask(fileTaskDto);
            if (Objects.isNull(fileDo)) {
                failIntegrityCheck(uploadContext, socketChannelContext, simpleChannelContext,
                        "文件数据保存失败");
                return;
            }

            // 3. 发送完成 ACK 文件结束上传，uploadContext内部已经设置了fileId和taskId
            uploadContext.setFileId(fileDo.getId());
            try {
                sendAckFrame(socketChannelContext, uploadContext, "success", null);
            } catch (IOException ackError) {
                // 文件和任务已成功入库。ACK 失败时保留成功结果，客户端可通过 RESUME_CHECK 幂等获取 complete。
                log.warn("上传成功但终态 ACK 发送失败: taskId={}, fileId={}",
                        uploadContext.getRequestTaskId(), uploadContext.getFileId(), ackError);
            }
            // 4. 删除断点记录（上传完成）
            if (StringUtils.isNotBlank(uploadContext.getMd5())) {
                CheckpointManager.removeCheckpoint(uploadContext.getMd5(), uploadContext.getUserId());
                log.debug("上传完成，删除断点记录: MD5={}", uploadContext.getMd5());
            }
            // 5. 释放资源
            this.realeaseResource(uploadContext, socketChannelContext);
            log.info("=> 文件上传完成，当前上传任务所有资源均已释放完毕: taskId={}, fileId={}, fileName={}, size={}, 耗时={}ms",
                uploadContext.getRequestTaskId(),
                uploadContext.getFileId(),
                uploadContext.getFileName(),
                uploadContext.getBytesWritten(),
                System.currentTimeMillis() - uploadContext.getStartTime().atZone(java.time.ZoneId.systemDefault())
                        .toInstant().toEpochMilli());
        } catch (Exception e) {
            log.error("上传完成校验与入库失败: taskId={}", uploadContext.getRequestTaskId(), e);
            if (uploadContext.getFileId() != null) {
                // 正式记录已经创建后禁止删除物理文件，保留幂等成功终态。
                if (StringUtils.isNotBlank(uploadContext.getMd5())) {
                    CheckpointManager.removeCheckpoint(uploadContext.getMd5(), uploadContext.getUserId());
                }
                realeaseResource(uploadContext, socketChannelContext);
            } else {
                failIntegrityCheck(uploadContext, socketChannelContext, simpleChannelContext,
                        "上传完成处理失败");
            }
        }
    }

    private void failIntegrityCheck(FileUploadContext uploadContext,
            SocketChannelContext socketChannelContext,
            SimpleChannelContext simpleChannelContext,
            String message) {
        try {
            if (uploadContext.getFileTaskId() != null) {
                FileTaskDto failedTask = new FileTaskDto();
                failedTask.setId(uploadContext.getFileTaskId());
                failedTask.setStatus(FileTaskStatusEnum.UPLOAD_FAIL.getCode());
                failedTask.setCurrentOffset(0L);
                failedTask.setGmtModified(new Date());
                fileTaskService.update(failedTask);
            }
            if (StringUtils.isNotBlank(uploadContext.getMd5())) {
                CheckpointManager.removeCheckpoint(uploadContext.getMd5(), uploadContext.getUserId());
            }
            uploadContext.markFailed(message);
            try {
                sendAckFrame(socketChannelContext, uploadContext, "error", message);
            } catch (IOException ackError) {
                log.warn("上传失败 ACK 发送失败: taskId={}", uploadContext.getRequestTaskId(), ackError);
            }
        } finally {
            realeaseResource(uploadContext, socketChannelContext);
            simpleChannelContext.setNeedStop(Boolean.TRUE);
        }
    }

    /**
     * 释放资源
     * 
     * @param fileUploadContext
     * @param socketChannelContext
     */
    private void realeaseResource(FileUploadContext fileUploadContext, SocketChannelContext socketChannelContext) {
        if (fileUploadContext == null) {
            if (socketChannelContext != null && socketChannelContext.getSocketChannel() != null) {
                WriteQueueHelper.closeAfterPendingWrites(socketChannelContext);
            }
            return;
        }
        // 1. 释放并发许可
        fileUploadContext.releaseSemaphore(uploadSemaphore);
        // 2. 清理资源（包含本次文件上传任务和解析器）
        uploadContextMap.remove(fileUploadContext.getRequestTaskId());
        uploadChannelContextMap.remove(fileUploadContext.getRequestTaskId());
        rebalanceUploadRateLimiters();
        // 3. 释放文件传输解析器
        parserMap.remove(socketChannelContext.getRemoteAddress());
        // 4. 移除文件传输任务对应通道缓存数据
        AbstractEventHandler.channelDataMap.remove(socketChannelContext.getRemoteAddress());
        // 5. 关闭通道
        WriteQueueHelper.closeAfterPendingWrites(socketChannelContext);
    }

    /**
     * 获取当前连接的活跃上传上下文
     * <p>
     * *** BUG FIX: 优先通过 currentUploadTaskId 精确查找（O(1)），再以 remoteAddress 兜底 ***
     * 原来以 remoteAddress 轮询 ConcurrentHashMap.values() 作为主路径，在多客户端并发上传
     * 场景下可能因遍历无序而返回错误的 Context，造成数据写入错文件。
     * 修复后：taskId 直接 Map.get() 是确定性查找，不会出现歧义。
     */
    private FileUploadContext getActiveUploadContext(SocketChannelContext socketChannelContext) {
        String remoteAddress = socketChannelContext.getRemoteAddress();

        // *** 主路径：优先通过 channel 上绑定的 taskId 精确查找（O(1)，无歧义） ***
        // createAndInitializeUploadContext 在成功创建 Context 后会将 taskId 写入 channel 属性
        String currentUploadTaskId = (String) socketChannelContext.getAttribute("currentUploadTaskId");
        if (StringUtils.isNotBlank(currentUploadTaskId)) {
            FileUploadContext ctx = uploadContextMap.get(currentUploadTaskId);
            if (ctx != null && ctx.getStatus() == FileUploadContext.UploadStatus.UPLOADING) {
                // 断点续传重连时远端口可能变化，顺便更新 remoteAddress 保证下次也能直接命中
                if (remoteAddress != null && !remoteAddress.equals(ctx.getRemoteAddress())) {
                    ctx.setRemoteAddress(remoteAddress);
                    log.debug("续传重连，更新 remoteAddress: taskId={}, newAddr={}", currentUploadTaskId, remoteAddress);
                }
                return ctx;
            }
        }

        // *** 兜底路径：通过 remoteAddress 遍历匹配（应对 taskId 未写入 channel 属性的极端情况） ***
        // 注意：ConcurrentHashMap.values() 遍历无序，若同一 IP 有多个并发上传任务可能误匹配
        // 但此处已作为兜底且生产环境同 IP 多并发概率低，可接受
        for (FileUploadContext ctx : uploadContextMap.values()) {
            if (ctx.getStatus() == FileUploadContext.UploadStatus.UPLOADING
                    && remoteAddress != null
                    && remoteAddress.equals(ctx.getRemoteAddress())) {
                // 找到后将 taskId 回写到 channel，下次走主路径
                socketChannelContext.putAttribute("currentUploadTaskId", ctx.getRequestTaskId());
                return ctx;
            }
        }

        return null;
    }

    /**
     * 发送断点应答帧（RESUME_ACK）
     */
    private void sendResumeAck(SocketChannelContext ctx, FileUploadContext uploadContext,
            String status, long uploadedSize, String message) throws IOException {
        sendResumeAck(ctx, uploadContext, null, status, uploadedSize, message);
    }

    private void sendResumeAck(SocketChannelContext ctx, FileUploadContext uploadContext,
            String requestTaskId, String status, long uploadedSize, String message) throws IOException {
        JSONObject ackJson = new JSONObject();
        ackJson.put("taskId", UploadResponseTaskIdResolver.resolve(uploadContext, requestTaskId));
        // 文件Id在断点应答帧先不传
        // ackJson.put("fileId", uploadContext.getFileId());
        ackJson.put("status", status);
        ackJson.put("uploadedSize", uploadedSize);
        ackJson.put("message", message);
        if (uploadContext != null && uploadContext.getFileId() != null) {
            ackJson.put("fileId", uploadContext.getFileId());
        }
        ackJson.put("initialChunkSize", config.getAdaptiveChunkInitialBytes());
        ackJson.put("minChunkSize", config.getAdaptiveChunkMinBytes());
        ackJson.put("maxChunkSize", config.getAdaptiveChunkMaxBytes());
        ackJson.put("initialAckWindow", config.getAdaptiveAckInitialBytes());
        ackJson.put("maxAckWindow", config.getAdaptiveAckMaxBytes());

        byte[] ackData = ackJson.toJSONString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(FileUploadFrame.HEADER_LENGTH + ackData.length);
        buffer.put(FileUploadFrame.MAGIC);
        buffer.put((byte) FileUploadFrame.FrameType.RESUME_ACK.getCode());
        buffer.put((byte) 0);
        buffer.putInt(ackData.length);
        buffer.put(ackData);
        buffer.flip();

        WriteQueueHelper.submitWrite(ctx, buffer);
        log.debug("发送断点应答: 客户端地址={}, 应答数据={}", ctx.getRemoteAddress(), JSON.toJSONString(ackJson));
    }

    /**
     * 发送 ACK 帧给客户端（使用 NIO 事件驱动的写操作）
     */
    private void sendAckFrame(SocketChannelContext socketChannelContext,
            FileUploadContext uploadContext,
            String status,
            String message) throws IOException {
        sendAckFrame(socketChannelContext, uploadContext, null, status, message, null);
    }

    private void sendAckFrame(SocketChannelContext socketChannelContext,
            FileUploadContext uploadContext,
            String requestTaskId,
            String status,
            String message) throws IOException {
        sendAckFrame(socketChannelContext, uploadContext, requestTaskId, status, message, null);
    }

    private void sendAckFrame(SocketChannelContext socketChannelContext,
            FileUploadContext uploadContext,
            String requestTaskId,
            String status,
            String message,
            Long uploadedSize) throws IOException {
        sendAckFrame(socketChannelContext, uploadContext, requestTaskId, status, message, uploadedSize, null);
    }

    private void sendAckFrame(SocketChannelContext socketChannelContext,
            FileUploadContext uploadContext,
            String requestTaskId,
            String status,
            String message,
            Long uploadedSize,
            UploadBackpressureDecision decision) throws IOException {
        try {
            SocketChannel socketChannel = socketChannelContext.getSocketChannel();
            if (socketChannel == null || !socketChannel.isOpen()) {
                log.warn("通道已关闭，无法发送 ACK");
                return;
            }

            // 构建 ACK 响应 JSON
            JSONObject ackJson = UploadAckPayloadBuilder.build(
                    uploadContext, requestTaskId, status, message, uploadedSize, decision);
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
            log.debug("发送 ACK 帧: 客户端地址={}, 向客户端响应的数据为={}", socketChannelContext.getRemoteAddress(),
                    JSON.toJSONString(ackJson));
        } catch (Exception e) {
            log.error("发送 ACK 帧失败, 客户端地址={}, error = {}", socketChannelContext.getRemoteAddress(),
                    ExceptionUtils.getStackTrace(e));
            throw e;
        }
    }

    private UploadBackpressureDecision createBackpressureDecision(
            SocketChannelContext socketChannelContext,
            FileUploadContext uploadContext) {
        int activeUploads = Math.max(1, uploadContextMap.size());
        double queueRatio = WorkerThreadPool.getQueueUtilization(socketChannelContext.getRemoteAddress());
        long globalCapacity = Math.max(1L, config.getGlobalBucketCapacity());
        double globalBudgetRatio = Math.min(
                1D,
                (double) globalRateLimiter.getAvailableTokens() / globalCapacity);
        return uploadBackpressureController.decide(
                ServerResourceMonitor.getInstance().getCurrentLevel(),
                uploadContext.getWindowWriteMillis(),
                queueRatio,
                activeUploads,
                globalBudgetRatio);
    }

    /**
     * 清理指定连接的资源（断连时调用）
     * 删除未完成上传的临时文件和数据库记录
     * 
     * @param remoteAddress 客户端远程地址
     */
    /**
     * 清理指定连接的资源（断连时调用）
     * 删除未完成上传的临时文件和数据库记录
     * 
     * @param remoteAddress 客户端远程地址
     */
    public static void cleanupConnection(String remoteAddress) {
        // 清理解析器
        parserMap.remove(remoteAddress);

        // 清理该连接相关的上传上下文, 如果存在未完成人传输任务则设置断点信息
        uploadContextMap.entrySet().removeIf(entry -> {
            FileUploadContext ctx = entry.getValue();
            // 只清理属于该客户端且未完成的上传
            if (remoteAddress.equals(ctx.getRemoteAddress())
                    && ctx.getStatus() == FileUploadContext.UploadStatus.UPLOADING) {
                cleanupContext(ctx);
                return true; // 从 Map 中移除
            }
            return false;
        });
    }

    /**
     * 检查并冻结超时任务，即最后一次该任务有效数据的记录时间到当前任务检查时间之间的间隔超过阈值，说明客户端暂停或是
     * 出现了崩溃，则清理文件上传任务元数据
     * 
     * @param idleThreshold 空闲阈值（毫秒）
     */
    public static void checkAndFreezeIdleTasks(long idleThreshold) {
        long now = System.currentTimeMillis();
        uploadContextMap.entrySet().removeIf(entry -> {
            FileUploadContext ctx = entry.getValue();
            if (now - ctx.getLastActiveTime() > idleThreshold
                    && ctx.getStatus() == FileUploadContext.UploadStatus.UPLOADING) {
                log.info("任务超时，冻结清理: taskId={}, lastActive={}", ctx.getFileTaskId(), new Date(ctx.getLastActiveTime()));
                cleanupContext(ctx);
                return true;
            }
            return false;
        });
    }

    /**
     * 清理单个上下文资源
     */
    private static void cleanupContext(FileUploadContext ctx) {
        try {
            log.warn("清理上传上下文: requestTaskId={}, fileName={}, remoteAddress={}, uploaded={}/{}",
                    ctx.getRequestTaskId(),
                    ctx.getFileName(),
                    ctx.getRemoteAddress(),
                    ctx.getBytesWritten(),
                    ctx.getFileSize());

            // *** 关键步骤：在读取任何偏移量之前，先强制刷盘并关闭通道 ***
            // 这确保 OS page cache 中的所有数据都已持久化到磁盘
            // 避免内存计数器与磁盘实际字节数不一致的问题
            ctx.closeFileChannel();

            // 1. 保存断点而非删除文件（支持断点续传）
            if (ctx.getMd5() != null && ctx.getBytesWritten() > 0) {

                // 从磁盘读取实际文件大小，作为断点偏移量的唯一真实来源
                // 这样无论内存计数器是否精准，磁盘上有多少字节就续传多少，100% 一致
                long actualDiskSize = ctx.getBytesWritten(); // fallback: in-memory counter
                if (ctx.getFilePath() != null) {
                    try {
                        java.nio.file.Path filePath = java.nio.file.Paths.get(ctx.getFilePath());
                        if (java.nio.file.Files.exists(filePath)) {
                            actualDiskSize = java.nio.file.Files.size(filePath);
                            if (actualDiskSize != ctx.getBytesWritten()) {
                                log.warn("磁盘实际大小({})与内存计数器({})不一致，以磁盘大小为准",
                                        actualDiskSize, ctx.getBytesWritten());
                            }
                        }
                    } catch (Exception e) {
                        log.error("读取磁盘文件大小失败，回退使用内存计数器: taskId={}", ctx.getRequestTaskId(), e);
                    }
                }

                // 将此时文件传输任务此刻的数据写入缓存，方便后续重传恢复任务进行处理
                UploadCheckpoint checkpoint = new UploadCheckpoint();
                checkpoint.setMd5(ctx.getMd5());
                checkpoint.setFileName(ctx.getFileName());
                checkpoint.setFileSize(ctx.getFileSize());
                checkpoint.setUploadedSize(actualDiskSize); // 使用磁盘真实大小
                checkpoint.setFilePath(ctx.getFilePath());
                checkpoint.setFileId(ctx.getFileId());
                checkpoint.setFileTaskId(ctx.getFileTaskId());
                checkpoint.setRequestTaskId(ctx.getRequestTaskId());
                checkpoint.setUserId(ctx.getUserId());
                checkpoint.setUserName(ctx.getUserName());
                checkpoint.setUpdateTime(java.time.LocalDateTime.now());
                checkpoint.setCreateTime(ctx.getStartTime());
                checkpoint.setMd5(ctx.getMd5());
                CheckpointManager.saveCheckpoint(checkpoint);
                log.info("已保存断点，支持续传: MD5={}, 磁盘实际大小={} ({:.2f}%)",
                        ctx.getMd5(), actualDiskSize,
                        ctx.getFileSize() > 0 ? actualDiskSize * 100.0 / ctx.getFileSize() : 0.0);
                // 持久化到数据库（PAUSED状态），使用磁盘真实大小作为 current_offset
                if (ctx.getFileTaskId() != null) {
                    try {
                        FileTaskDto fileTaskDto = new FileTaskDto();
                        fileTaskDto.setId(ctx.getFileTaskId());
                        fileTaskDto.setStatus(FileTaskStatusEnum.PAUSED.getCode());
                        fileTaskDto.setCurrentOffset(actualDiskSize); // 使用磁盘真实大小
                        fileTaskDto.setLastActiveTime(new Date());
                        fileTaskDto.setGmtModified(new Date());
                        fileTaskService.update(fileTaskDto);
                    } catch (Exception e) {
                        log.error("暂停任务持久化失败: taskId={}", ctx.getFileTaskId(), e);
                    }
                }

            } else {
                // 无 MD5 或未上传任何数据，删除文件
                ctx.markFailed("任务中断或超时");
                log.info("无断点信息，删除临时文件: fileName={}", ctx.getFileName());
            }
            // 2. 释放并发许可
            ctx.releaseSemaphore(uploadSemaphore);
            uploadChannelContextMap.remove(ctx.getRequestTaskId());
            rebalanceUploadRateLimiters();
            // 注意：文件通道已在步骤开头关闭，此处无需再次调用 closeFileChannel()
            // 3. 删除数据库记录（仅在无断点时）
            if (ctx.getMd5() == null && ctx.getFileId() != null) {
                try {
                    // FileService fileService = NioServerContext.getFileService();
                    // if (fileService != null) {
                    fileService.deleteFileById(ctx.getFileId());
                    // }
                } catch (Exception e) {
                    log.error("删除数据库记录失败: fileId={}", ctx.getFileId(), e);
                }
            }
        } catch (Exception e) {
            log.error("清理上下文失败: taskId={}", ctx.getRequestTaskId(), e);
        }
    }

    private static void rebalanceUploadRateLimiters() {
        int activeUploads = uploadChannelContextMap.size();
        if (activeUploads <= 0) {
            return;
        }
        long fairRate = config.calculateDynamicRate(activeUploads);
        fairRate = Math.max(1L, (long) (fairRate * AdaptiveThrottlePolicy.uploadRateMultiplier()));
        long capacity = Math.max(fairRate, fairRate * config.getBucketCapacityMultiplier());
        for (SocketChannelContext channelContext : uploadChannelContextMap.values()) {
            channelContext.setRateLimiter(new TokenBucketRateLimiter(fairRate, capacity));
        }
        log.debug("上传公平带宽已重算: activeUploads={}, perConnectionRate={} B/s",
                activeUploads, fairRate);
    }

    private void applyAuthenticatedIdentity(FileUploadRequest request) {
        if (request == null) {
            throw new SecurityException("上传请求不能为空");
        }
        TransferTokenService.ValidationResult identity = TransferTokenFactory.getInstance()
                .validateToken(request.getTransferToken());
        if (!identity.isValid()) {
            throw new SecurityException(identity.getMessage());
        }
        if (request.getUserId() != null && !identity.getUserId().equals(request.getUserId().longValue())) {
            throw new SecurityException("上传用户身份不一致");
        }
        if (StringUtils.isNotBlank(request.getUserName())
                && !StringUtils.equals(identity.getUserName(), request.getUserName())) {
            throw new SecurityException("上传用户身份不一致");
        }
        request.setUserId(identity.getUserId().intValue());
        request.setUserName(identity.getUserName());
    }

    private String resolveUploadDirectory(FileUploadRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("上传请求不能为空");
        }
        if (UPLOAD_PURPOSE_CHAT_ATTACHMENT.equalsIgnoreCase(request.getUploadPurpose())) {
            FileDto attachmentDirectory = fileService.ensureChatAttachmentDirectory(
                    request.getUserId(), request.getUserName());
            if (attachmentDirectory == null || attachmentDirectory.getId() == null
                    || attachmentDirectory.getId() <= 0L) {
                throw new IllegalStateException("聊天附件目录准备失败");
            }
            request.setDirId(attachmentDirectory.getId());
            return attachmentDirectory.getFilePath();
        }
        if (request.getDirId() == null || request.getDirId() <= 0L) {
            throw new IllegalArgumentException("上传目录ID无效");
        }
        return fileService.ensureUploadDirectory(
                request.getDirId(), request.getUserId(), request.getUserName());
    }

}
