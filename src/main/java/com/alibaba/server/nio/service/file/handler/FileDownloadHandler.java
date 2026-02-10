package com.alibaba.server.nio.service.file.handler;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.server.nio.core.server.BasicServer;
import com.alibaba.server.nio.core.server.NioServerContext;
import com.alibaba.server.nio.handler.event.concret.WriteQueueHelper;
import com.alibaba.server.nio.handler.pipe.ChannelContext;
import com.alibaba.server.nio.handler.pipe.standard.SimpleChannelContext;
import com.alibaba.server.nio.model.ChannelEventModel;
import com.alibaba.server.nio.model.SocketChannelContext;
import com.alibaba.server.nio.model.TransportDataModel;
import com.alibaba.server.nio.model.file.FileDownloadContext;
import com.alibaba.server.nio.model.file.FileDownloadFrame;
import com.alibaba.server.nio.model.file.FileDownloadFrame.FrameType;
import com.alibaba.server.nio.model.file.FileDownloadFrame.FrameType;
import com.alibaba.server.nio.repository.file.service.FileService;
import com.alibaba.server.nio.repository.file.service.FileTaskService;
import com.alibaba.server.nio.repository.file.service.dto.FileDto;
import com.alibaba.server.nio.repository.file.service.dto.FileTaskDto;
import com.alibaba.server.nio.repository.file.service.param.FileQueryParam;
import com.alibaba.server.common.FileTaskStatusEnum;
import com.alibaba.server.nio.service.api.AbstractChannelHandler;
import com.alibaba.server.nio.service.file.parser.FrameDownloadParser;
import com.alibaba.server.util.LocalTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文件下载处理器
 * 
 * 业务流程：
 * 1. 接收客户端下载请求（包含文件ID或文件路径）
 * 2. 校验 DB 记录和文件系统中文件是否存在
 * 3. 发送文件信息给客户端
 * 4. 传输文件流数据
 * 5. 发送完成通知并关闭连接
 * 
 * @author YSFY
 */
@Slf4j
@SuppressWarnings("all")
public class FileDownloadHandler extends AbstractChannelHandler {

    /**
     * 帧解析器缓存：remoteAddress -> FrameDownloadParser
     */
    private static final ConcurrentHashMap<String, FrameDownloadParser> parserMap = new ConcurrentHashMap<>();

    /**
     * 下载上下文缓存：taskId -> FileDownloadContext
     */
    private static final ConcurrentHashMap<String, FileDownloadContext> contextMap = new ConcurrentHashMap<>();

    /**
     * 全局并发下载控制信号量（最多5个并发下载）
     */
    private static final java.util.concurrent.Semaphore DOWNLOAD_SEMAPHORE = new java.util.concurrent.Semaphore(5);

    /**
     * 最大待发送缓冲区数量（流控阈值）
     * 50 × 64KB = 3.2MB 每连接最大内存占用
     */
    private static final int MAX_PENDING_BUFFERS = 50;

    /**
     * 文件读取缓冲区大小（64KB）
     */
    private static final int BUFFER_SIZE = 65536;

    @Override
    public void handler(Object o, ChannelContext channelContext) throws IOException {
        TransportDataModel transportDataModel = (TransportDataModel) o;
        SimpleChannelContext simpleChannelContext = (SimpleChannelContext) channelContext;
        SocketChannelContext socketChannelContext = simpleChannelContext.getSocketChannelContext();

        if (socketChannelContext == null) {
            log.error("SocketChannelContext 为空，无法处理");
            return;
        }

        // 检查 handlerType，非 DOWNLOAD 则直接返回，让 Pipeline 继续执行
        if (!"DOWNLOAD".equals(socketChannelContext.getHandlerType())) {
            return;
        }

        List<ChannelEventModel.GroupData> waitHandleDataList = transportDataModel.getWaitHandleDataList();
        if (CollectionUtils.isEmpty(waitHandleDataList)) {
            return;
        }

        // 获取或创建帧解析器
        FrameDownloadParser parser = getOrCreateParser(socketChannelContext);

        // 逐个处理待处理数据
        for (ChannelEventModel.GroupData groupData : waitHandleDataList) {
            List<FileDownloadFrame> frames = parser.parse(groupData.getBytes());

            // 处理解析完成的帧
            for (FileDownloadFrame frame : frames) {
                handleFrame(frame, socketChannelContext, simpleChannelContext);
            }
        }
    }

    /**
     * 获取文件服务
     */
    private FileService getFileService() {
        return BasicServer.classPathXmlApplicationContext.getBean(FileService.class);
    }

    /**
     * 获取或创建帧解析器
     */
    private FrameDownloadParser getOrCreateParser(SocketChannelContext socketChannelContext) {
        String remoteAddress = socketChannelContext.getRemoteAddress();
        return parserMap.computeIfAbsent(remoteAddress, k -> {
            log.debug("为下载通道 {} 创建新的 FrameDownloadParser", remoteAddress);
            return new FrameDownloadParser();
        });
    }

    /**
     * 处理单个完整的帧
     */
    private void handleFrame(FileDownloadFrame frame,
            SocketChannelContext socketChannelContext,
            SimpleChannelContext simpleChannelContext) throws IOException {

        if (frame.getType() == null) {
            log.warn("帧类型为空，跳过处理");
            return;
        }

        // log.debug("处理下载请求帧: type={}, dataLength={}", frame.getType(),
        // frame.getDataLength());

        switch (frame.getType()) {
            case META_FRAME:
                // 客户端发送的下载请求
                handleDownloadRequest(frame, socketChannelContext, simpleChannelContext);
                break;
            case ACK_FRAME:
                // 客户端确认，可以开始传输
                handleClientAck(frame, socketChannelContext, simpleChannelContext);
                break;
            default:
                log.warn("下载处理器收到未知帧类型: {}", frame.getType());
                break;
        }
    }

    /**
     * 处理下载请求（META_FRAME）
     * 解析请求，校验文件，发送文件信息
     */
    private void handleDownloadRequest(FileDownloadFrame frame,
            SocketChannelContext socketChannelContext,
            SimpleChannelContext simpleChannelContext) throws IOException {
        try {
            // 1. 解析请求 JSON, 提取要下载的文件Id
            String jsonData = frame.getDataAsString();
            JSONObject request = JSON.parseObject(jsonData);
            Long fileId = request.getLong("fileId");
            // 客户端生成的任务ID，用于标识本次下载会话
            String taskId = request.getString("taskId");
            // 起始偏移量（断点续传）
            long startOffset = request.getLongValue("startOffset");

            log.info("[ {} ] FileDownloadHandler | --> 接收到下载请求: fileId={}, taskId={}, startOffset={}",
                    LocalTime.formatDate(LocalDateTime.now()), fileId, taskId, startOffset);

            if (org.apache.commons.lang.StringUtils.isBlank(taskId)) {
                sendErrorFrame(socketChannelContext, "taskId不能为空");
                return;
            }

            // 2. 从 DB 校验文件记录
            FileQueryParam queryParam = new FileQueryParam();
            queryParam.setId(fileId);
            FileDto fileDto = getFileService().getFileById(queryParam);
            if (fileDto == null || fileDto.getId() == null) {
                log.warn("文件记录不存在: fileId={}", fileId);
                sendErrorFrame(socketChannelContext, "文件不存在");
                return;
            }

            // 3. 校验文件系统中文件是否存在
            File file = new File(fileDto.getFilePath());
            if (!file.exists() || !file.isFile()) {
                log.warn("文件系统中文件不存在: path={}", fileDto.getFilePath());
                sendErrorFrame(socketChannelContext, "文件不存在");
                return;
            }

            // 4. 创建/更新上下文
            FileDownloadContext context = new FileDownloadContext();
            context.setTaskId(taskId);
            context.setFileId(fileId);
            context.setFile(file);
            context.setFileName(fileDto.getFileName());
            context.setFileSize(file.length());
            context.setStartOffset(startOffset);
            context.setCurrentOffset(startOffset); // 从指定位置开始
            context.setRemoteAddress(socketChannelContext.getRemoteAddress());

            // 放入缓存
            contextMap.put(taskId, context);

            // 5. 发送文件信息给客户端 (META_FRAME)
            JSONObject fileInfo = new JSONObject();
            fileInfo.put("fileId", fileDto.getId());
            fileInfo.put("fileName", fileDto.getFileName());
            fileInfo.put("fileSize", file.length());
            fileInfo.put("fileType", fileDto.getFileType());
            fileInfo.put("taskId", taskId); // 回传taskId
            fileInfo.put("startOffset", startOffset); // 确认起始位置

            sendFrame(socketChannelContext, FrameType.ACK_FRAME, fileInfo.toJSONString());

            log.info("[ {} ] FileDownloadHandler | --> 已发送文件信息，等待客户端确认: fileName={}, size={}, startOffset={}",
                    LocalTime.formatDate(LocalDateTime.now()), fileDto.getFileName(), file.length(), startOffset);
        } catch (Exception e) {
            log.error("处理下载请求失败", e);
            sendErrorFrame(socketChannelContext, "处理请求失败: " + e.getMessage());
        }
    }

    /**
     * 处理客户端确认（ACK_FRAME）
     * 开始传输文件流
     */
    private void handleClientAck(FileDownloadFrame frame,
            SocketChannelContext socketChannelContext,
            SimpleChannelContext simpleChannelContext) throws IOException {
        try {
            String jsonData = frame.getDataAsString();
            JSONObject ack = JSON.parseObject(jsonData);
            String status = ack.getString("status");
            String taskId = ack.getString("taskId"); // 必须携带taskId

            if (org.apache.commons.lang.StringUtils.isBlank(taskId)) {
                log.warn("ACK帧缺少taskId");
                return;
            }

            // 获取上下文
            FileDownloadContext context = contextMap.get(taskId);
            if (context == null) {
                log.warn("下载上下文不存在或已过期: taskId={}", taskId);
                sendErrorFrame(socketChannelContext, "会话已失效，请重新请求");
                return;
            }

            if (!"ready".equals(status)) {
                log.warn("客户端取消或未准备好: status={}", status);
                contextMap.remove(taskId);
                closeConnection(socketChannelContext, simpleChannelContext);
                return;
            }

            log.info("[ {} ] FileDownloadHandler | --> 客户端已准备好，开始传输文件: fileName={}, offset={}",
                    LocalTime.formatDate(LocalDateTime.now()), context.getFileName(), context.getCurrentOffset());

            // 获取下载许可（限制并发下载数）
            boolean acquired = false;
            try {
                acquired = DOWNLOAD_SEMAPHORE.tryAcquire(5, java.util.concurrent.TimeUnit.SECONDS);
                if (!acquired) {
                    log.warn("服务器繁忙，下载任务排队超时: taskId={}", taskId);
                    sendErrorFrame(socketChannelContext, "服务器繁忙，请稍后重试");
                    contextMap.remove(taskId);
                    closeConnection(socketChannelContext, simpleChannelContext);
                    return;
                }

                log.info("[ {} ] FileDownloadHandler | --> 获得下载许可，当前可用许可数: {}",
                        LocalTime.formatDate(LocalDateTime.now()), DOWNLOAD_SEMAPHORE.availablePermits());

                // 开始传输文件
                transferFile(context, socketChannelContext, simpleChannelContext);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("等待下载许可被中断: taskId={}", taskId, e);
                sendErrorFrame(socketChannelContext, "服务器内部错误");
                contextMap.remove(taskId);
                closeConnection(socketChannelContext, simpleChannelContext);
            } finally {
                // 释放下载许可
                if (acquired) {
                    DOWNLOAD_SEMAPHORE.release();
                    log.info("[ {} ] FileDownloadHandler | --> 释放下载许可，当前可用许可数: {}",
                            LocalTime.formatDate(LocalDateTime.now()), DOWNLOAD_SEMAPHORE.availablePermits());
                }
            }

        } catch (Exception e) {
            log.error("处理客户端确认失败", e);
            sendErrorFrame(socketChannelContext, "传输失败: " + e.getMessage());
        }
    }

    /**
     * 传输文件流
     */
    private void transferFile(FileDownloadContext context,
            SocketChannelContext socketChannelContext,
            SimpleChannelContext simpleChannelContext) throws IOException {

        try {
            // 初始化文件通道
            context.openFileChannel();
            // 定位到断点位置
            if (context.getCurrentOffset() > 0) {
                context.getRandomAccessFile().seek(context.getCurrentOffset());
            }

            // 分块读取并发送 - 使用优化后的缓冲区大小
            ByteBuffer readBuffer = ByteBuffer.allocate(BUFFER_SIZE); // 64KB
            int totalWaitCount = 0;
            long lastLogTime = System.currentTimeMillis();

            while (true) {
                // ========== 应用层流控：检查发送队列大小 ==========
                java.util.concurrent.ConcurrentLinkedQueue<ByteBuffer> queue = socketChannelContext
                        .getPendingWriteQueue();
                int queueSize = queue.size();
                int waitCount = 0;

                // 当队列积压超过阈值时，暂停读取文件，等待队列消化
                while (queueSize > MAX_PENDING_BUFFERS) {
                    waitCount++;
                    totalWaitCount++;

                    // 每等待 100 次（5秒）记录一次日志
                    if (waitCount % 100 == 0) {
                        log.info("[ {} ] FileDownloadHandler | --> 流控等待中，队列积压: {} 个缓冲区，已等待: {} 次",
                                LocalTime.formatDate(LocalDateTime.now()), queueSize, waitCount);
                    }

                    try {
                        Thread.sleep(50); // 等待 50ms
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("传输被中断", e);
                    }

                    // 检查通道是否仍然打开
                    if (socketChannelContext.getSocketChannel() == null
                            || !socketChannelContext.getSocketChannel().isOpen()) {
                        throw new IOException("通道已关闭");
                    }

                    queueSize = queue.size();
                }

                // 定期记录流控统计信息（每 10 秒）
                long now = System.currentTimeMillis();
                if (now - lastLogTime >= 10000) {
                    log.info(
                            "[ {} ] FileDownloadHandler | --> 传输进度: fileName={}, progress={}/{}, queueSize={}, totalWaits={}",
                            LocalTime.formatDate(LocalDateTime.now()),
                            context.getFileName(),
                            context.getCurrentOffset(),
                            context.getFileSize(),
                            queueSize,
                            totalWaitCount);
                    lastLogTime = now;
                }

                // 持久化进度 (每5秒或每10MB)
                if (now - context.getLastSavedTime() > 5000 ||
                        context.getCurrentOffset() - context.getLastSavedOffset() > 10 * 1024 * 1024) {
                    if (context.getFileTaskId() != null) {
                        try {
                            // 使用 fileTaskService 更新
                            fileTaskService.updateProgress(context.getFileTaskId(), context.getCurrentOffset());
                            context.setLastSavedTime(now);
                            context.setLastSavedOffset(context.getCurrentOffset());
                        } catch (Exception e) {
                            log.error("持久化下载进度失败: taskId={}", context.getFileTaskId(), e);
                        }
                    }
                }

                // ========== 读取文件数据 ==========
                readBuffer.clear();
                int bytesRead = context.readChunk(readBuffer);

                if (bytesRead == -1) {
                    break; // 文件读取完毕
                }

                if (bytesRead > 0) {
                    readBuffer.flip();
                    byte[] data = new byte[readBuffer.remaining()];
                    readBuffer.get(data);
                    sendDataFrame(socketChannelContext, data);
                }
            }

            // 等待所有 DATA_FRAME 发送完成（无限等待，但如果30秒无进展则认为卡死）
            log.info("等待所有数据帧发送完成...");
            WriteQueueHelper.waitForQueueDrain(socketChannelContext, 30000); // 30秒无进展超时

            // 发送结束帧
            JSONObject endJson = new JSONObject();
            endJson.put("status", "success");
            endJson.put("totalBytes", context.getFileSize());
            endJson.put("taskId", context.getTaskId());
            sendFrame(socketChannelContext, FrameType.END_FRAME, endJson.toJSONString());

            // 等待 END_FRAME 发送完成
            WriteQueueHelper.waitForQueueDrain(socketChannelContext, 10000); // 10秒无进展超时

            log.info("[ {} ] FileDownloadHandler | --> 文件传输完成: fileName={}, totalSize={}, transmitted={}",
                    LocalTime.formatDate(LocalDateTime.now()),
                    context.getFileName(), context.getFileSize(),
                    context.getCurrentOffset() - context.getStartOffset());

        } catch (Exception e) {
            log.error("文件传输失败: context={}", context, e);
            sendErrorFrame(socketChannelContext, "传输失败: " + e.getMessage());
        } finally {
            // 清理上下文和关闭文件句柄
            context.close();
            contextMap.remove(context.getTaskId());
            // 关闭连接
            closeConnection(socketChannelContext, simpleChannelContext);
        }
    }

    /**
     * 发送数据帧
     */
    private void sendDataFrame(SocketChannelContext socketChannelContext, byte[] data) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocateDirect(FileDownloadFrame.HEADER_LENGTH + data.length);
        buffer.put(FileDownloadFrame.MAGIC);
        buffer.put((byte) FrameType.DATA_FRAME.getCode());
        buffer.put((byte) 0);
        buffer.putInt(data.length);
        buffer.put(data);
        buffer.flip();

        WriteQueueHelper.submitWrite(socketChannelContext, buffer);
    }

    /**
     * 发送帧
     */
    private void sendFrame(SocketChannelContext socketChannelContext, FrameType type, String jsonData)
            throws IOException {
        byte[] data = jsonData.getBytes(StandardCharsets.UTF_8);

        ByteBuffer buffer = ByteBuffer.allocate(FileDownloadFrame.HEADER_LENGTH + data.length);
        buffer.put(FileDownloadFrame.MAGIC);
        buffer.put((byte) type.getCode());
        buffer.put((byte) 0);
        buffer.putInt(data.length);
        buffer.put(data);
        buffer.flip();

        WriteQueueHelper.submitWrite(socketChannelContext, buffer);
        log.debug("发送帧: type={}, dataLength={}", type, data.length);
    }

    /**
     * 发送错误帧
     */
    private void sendErrorFrame(SocketChannelContext socketChannelContext, String message) throws IOException {
        JSONObject errorJson = new JSONObject();
        errorJson.put("status", "error");
        errorJson.put("message", message);
        sendFrame(socketChannelContext, FrameType.ACK_FRAME, errorJson.toJSONString());
    }

    /**
     * 关闭连接
     */
    private void closeConnection(SocketChannelContext socketChannelContext,
            SimpleChannelContext simpleChannelContext) {
        try {
            String remote = socketChannelContext.getRemoteAddress();
            parserMap.remove(remote);
            // 清理该连接可能关联的所有 context (虽然 normally 只有一个 active download)
            contextMap.entrySet().removeIf(entry -> {
                if (urlMatch(entry.getValue().getRemoteAddress(), remote)) {
                    entry.getValue().close();
                    return true;
                }
                return false;
            });

            NioServerContext.closedAndRelease(socketChannelContext.getSocketChannel());
            simpleChannelContext.setNeedStop(true);
        } catch (Exception e) {
            log.error("关闭连接失败", e);
        }
    }

    // 简单的地址匹配辅助
    private boolean urlMatch(String addr1, String addr2) {
        return addr1 != null && addr1.equals(addr2);
    }

    /**
     * 清理指定连接的资源 (static method for global cleanup)
     */
    /**
     * 检查并冻结超时任务
     * 
     * @param idleThreshold 空闲阈值（毫秒）
     */
    public static void checkAndFreezeIdleTasks(long idleThreshold) {
        long now = System.currentTimeMillis();
        contextMap.entrySet().removeIf(entry -> {
            FileDownloadContext ctx = entry.getValue();
            if (now - ctx.getLastActiveTime() > idleThreshold) {
                log.info("下载任务超时，冻结清理: taskId={}, lastActive={}", ctx.getFileTaskId(),
                        new Date(ctx.getLastActiveTime()));
                cleanupContext(ctx);
                return true;
            }
            return false;
        });
    }

    /**
     * 清理指定连接的资源 (static method for global cleanup)
     */
    public static void cleanupConnection(String remoteAddress) {
        parserMap.remove(remoteAddress);
        // 清理 contextMap 中属于该 remoteAddress 的条目，并关闭文件句柄
        contextMap.entrySet().removeIf(entry -> {
            FileDownloadContext ctx = entry.getValue();
            if (remoteAddress.equals(ctx.getRemoteAddress())) {
                cleanupContext(ctx);
                return true;
            }
            return false;
        });
    }

    /**
     * 清理单个下载上下文资源
     */
    private static void cleanupContext(FileDownloadContext ctx) {
        try {
            // 持久化为 PAUSED 状态
            if (ctx.getFileTaskId() != null && ctx.getCurrentOffset() < ctx.getFileSize()) {
                try {
                    FileTaskDto fileTaskDto = new FileTaskDto();
                    fileTaskDto.setId(ctx.getFileTaskId());
                    fileTaskDto.setStatus(FileTaskStatusEnum.PAUSED.getCode());
                    fileTaskDto.setCurrentOffset(ctx.getCurrentOffset());
                    fileTaskDto.setLastActiveTime(new Date());
                    fileTaskDto.setGmtModified(new Date());
                    fileTaskService.update(fileTaskDto);
                    log.info("连接断开或超时，保存下载断点: taskId={}, offset={}", ctx.getFileTaskId(), ctx.getCurrentOffset());
                } catch (Exception e) {
                    log.error("暂停下载任务持久化失败: taskId={}", ctx.getFileTaskId(), e);
                }
            }
            ctx.close(); // 务必关闭文件句柄
        } catch (Exception e) {
            log.error("清理下载上下文失败: taskId={}", ctx.getTaskId(), e);
        }
    }
}
