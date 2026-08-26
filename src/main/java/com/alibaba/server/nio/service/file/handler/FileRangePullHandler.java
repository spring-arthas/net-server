package com.alibaba.server.nio.service.file.handler;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.server.nio.core.server.BasicServer;
import com.alibaba.server.common.BasicConstant;
import com.alibaba.server.nio.handler.event.concret.WriteQueueHelper;
import com.alibaba.server.nio.handler.pipe.ChannelContext;
import com.alibaba.server.nio.handler.pipe.standard.SimpleChannelContext;
import com.alibaba.server.nio.model.ChannelEventModel;
import com.alibaba.server.nio.model.SocketChannelContext;
import com.alibaba.server.nio.model.TransportDataModel;
import com.alibaba.server.nio.model.file.FileDownloadFrame;
import com.alibaba.server.nio.model.file.FileDownloadFrame.FrameType;
import com.alibaba.server.nio.model.file.RangePullSessionContext;
import com.alibaba.server.nio.repository.file.service.FileService;
import com.alibaba.server.nio.repository.file.service.dto.FileDto;
import com.alibaba.server.nio.repository.file.service.param.FileQueryParam;
import com.alibaba.server.nio.service.api.AbstractChannelHandler;
import com.alibaba.server.nio.service.file.adaptive.AdaptiveThrottlePolicy;
import com.alibaba.server.nio.service.file.adaptive.ServerResourceMonitor;
import com.alibaba.server.nio.service.file.parser.FrameDownloadParser;
import com.alibaba.server.nio.service.file.security.FileTransferAccessAuthorizer;
import com.alibaba.server.nio.service.file.security.TransferTokenFactory;
import com.alibaba.server.nio.service.file.security.TransferTokenService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Pull-Range 在线流式传输处理器。
 */
@Slf4j
@SuppressWarnings("all")
public class FileRangePullHandler extends AbstractChannelHandler {

    // 每个连接维护一个帧解析器，解决 TCP 粘包/半包问题
    private static final ConcurrentHashMap<String, FrameDownloadParser> parserMap = new ConcurrentHashMap<>();
    // taskId -> Pull-Range 会话，保存最近窗口和幂等信息
    private static final ConcurrentHashMap<String, RangePullSessionContext> sessionMap = new ConcurrentHashMap<>();

    // 拉流并发控制，避免窗口并发过高挤占下载资源
    private static final Semaphore STREAM_SEMAPHORE = new Semaphore(5);

    // 单次文件读取块大小（与旧下载处理器保持一致）
    private static final int BUFFER_SIZE = 65536;
    // 当待写队列积压超过该阈值，暂停读文件形成应用层背压
    private static final int MAX_PENDING_BUFFERS = 50;
    // 单窗口“无进展”超时阈值，防止长时间卡死
    private static final long WINDOW_TIMEOUT_MS = 15000L;

    private FileService getFileService() {
        return BasicServer.classPathXmlApplicationContext.getBean(FileService.class);
    }

    private FrameDownloadParser getOrCreateParser(SocketChannelContext socketChannelContext) {
        String remoteAddress = socketChannelContext.getRemoteAddress();
        return parserMap.computeIfAbsent(remoteAddress, k -> new FrameDownloadParser());
    }

    @Override
    public void handler(Object o, ChannelContext channelContext) throws IOException {
        TransportDataModel transportDataModel = (TransportDataModel) o;
        SimpleChannelContext simpleChannelContext = (SimpleChannelContext) channelContext;
        SocketChannelContext socketChannelContext = simpleChannelContext.getSocketChannelContext();
        if (socketChannelContext == null) {
            return;
        }

        String handlerType = socketChannelContext.getHandlerType();
        // 仅处理下载通道，以及已切换到 RANGE_PULL 的通道
        if (!"DOWNLOAD".equals(handlerType) && !"RANGE_PULL".equals(handlerType)) {
            return;
        }

        List<ChannelEventModel.GroupData> waitHandleDataList = transportDataModel.getWaitHandleDataList();
        if (waitHandleDataList == null || waitHandleDataList.isEmpty()) {
            return;
        }

        FrameDownloadParser parser = getOrCreateParser(socketChannelContext);
        for (ChannelEventModel.GroupData groupData : waitHandleDataList) {
            List<FileDownloadFrame> frames = parser.parse(groupData.getBytes());
            for (FileDownloadFrame frame : frames) {
                handleFrame(frame, socketChannelContext);
            }
        }
    }

    private void handleFrame(FileDownloadFrame frame, SocketChannelContext socketChannelContext) throws IOException {
        // Pull-Range 只从 META_FRAME 中读取控制参数
        if (frame == null || frame.getType() != FrameType.META_FRAME) {
            return;
        }

        JSONObject request;
        try {
            request = JSON.parseObject(frame.getDataAsString());
        } catch (Exception ex) {
            if ("RANGE_PULL".equals(socketChannelContext.getHandlerType())) {
                sendAckError(socketChannelContext, null, null, 40010, "invalid request json");
            }
            return;
        }

        String op = request.getString("op");
        Integer protocolVersion = request.getInteger("protocolVersion");
        // 新协议入口：必须 op=range_pull 且协议版本 >= 2
        boolean isRangePull = "range_pull".equals(op) && protocolVersion != null && protocolVersion >= 2;

        if (!isRangePull) {
            if ("RANGE_PULL".equals(socketChannelContext.getHandlerType())) {
                sendAckError(socketChannelContext, request.getString("taskId"), request.getString("requestId"),
                        40010, "invalid range_pull request");
            }
            return;
        }

        // 一旦识别为 range_pull，请求后续都由本处理器接管
        socketChannelContext.setHandlerType("RANGE_PULL");
        handleRangePullRequest(request, socketChannelContext);
    }

    private void handleRangePullRequest(JSONObject request, SocketChannelContext socketChannelContext) throws IOException {
        String taskId = request.getString("taskId");
        String requestId = request.getString("requestId");
        Long fileId = request.getLong("fileId");
        Long startOffset = request.getLong("startOffset");
        Long length = request.getLong("length");
        log.info("=>【在线视频拉流】taskId:{}, requestId:{}, fileId:{}, 拉取起始偏移startOffset:{}, 拉取长度length:{}",
            taskId, requestId, fileId, startOffset, length);

        if (StringUtils.isBlank(taskId) || StringUtils.isBlank(requestId)) {
            sendAckError(socketChannelContext, taskId, requestId, 40011, "missing taskId/requestId");
            return;
        }
        if (fileId == null || startOffset == null || length == null) {
            sendAckError(socketChannelContext, taskId, requestId, 40010, "invalid request json");
            return;
        }

        // 查询文件是否在DB依旧存在
        FileQueryParam queryParam = new FileQueryParam();
        queryParam.setId(fileId);
        FileDto fileDto = getFileService().getFileById(queryParam);
        if (fileDto == null || fileDto.getId() == null) {
            sendAckError(socketChannelContext, taskId, requestId, 40410, "file not found");
            return;
        }
        TransferTokenService.ValidationResult identity = TransferTokenFactory.getInstance()
                .validateToken(request.getString("transferToken"));
        if (!identity.isValid()) {
            sendAckError(socketChannelContext, taskId, requestId, 40310, identity.getMessage());
            return;
        }
        try {
            new FileTransferAccessAuthorizer().requireDownloadAccess(fileDto, identity);
        } catch (SecurityException e) {
            sendAckError(socketChannelContext, taskId, requestId, 40310, e.getMessage());
            return;
        }
        // 查询文件是否在文件系统存在
        String storageRoot = String.valueOf(BasicServer.getMap().get(BasicConstant.NIO_FILE_BASE_PATH_LINUX_MAC));
        File file = FileDownloadPathResolver.resolve(fileDto, null, storageRoot);
        if (file == null || !file.exists() || !file.isFile()) {
            sendAckError(socketChannelContext, taskId, requestId, 40410, "file not found");
            return;
        }

        long fileSize = file.length();
        // 统一范围校验：非法直接 41601；超出 EOF 自动裁剪为实际可发长度
        RangePullWindowValidator.ValidationResult validationResult =
                RangePullWindowValidator.validate(fileSize, startOffset, length);
        if (!validationResult.isValid()) {
            sendAckError(socketChannelContext, taskId, requestId, validationResult.getCode(), validationResult.getMessage());
            return;
        }

        // 本次拉流允许拉取的流范围长度
        long actualLength = validationResult.getActualLength();
        RangePullSessionContext session = getOrCreateSession(taskId, socketChannelContext.getRemoteAddress(), fileId, fileSize);
        // 幂等处理：同 requestId + 同窗口参数，直接回放 ACK/END，避免重复传输
        if (requestId.equals(session.getLastRequestId())
                && session.getCurrentWindowStart() == startOffset
                && session.getCurrentWindowLength() == actualLength
                && session.getWindowSentBytes() >= 0) {
            sendAckSuccess(socketChannelContext, taskId, requestId, fileId, fileSize, startOffset, actualLength);
            sendEndSuccess(socketChannelContext, taskId, requestId, startOffset + session.getWindowSentBytes(),
                    session.getWindowSentBytes(), startOffset + session.getWindowSentBytes() >= fileSize);
            session.touch();
            return;
        }

        // [修改] 自适应并发上限检查：拉流为随机读，对磁盘 IO 损耗最重，优先收紧
        int adaptiveMax = AdaptiveThrottlePolicy.rangePullMaxConcurrent();
        int currentStreams = 5 - STREAM_SEMAPHORE.availablePermits();
        if (currentStreams >= adaptiveMax) {
            log.warn("拉流并发已达自适应上限: current={}/{}, 压力等级={}",
                    currentStreams, adaptiveMax, ServerResourceMonitor.getInstance().getCurrentLevel());
            sendAckError(socketChannelContext, taskId, requestId, 42910,
                    "server busy, pressure=" + ServerResourceMonitor.getInstance().getCurrentLevel());
            return;
        }

        boolean acquired = false;
        try {
            // 获取窗口发送许可，失败则快速返回 server busy
            acquired = STREAM_SEMAPHORE.tryAcquire(2, TimeUnit.SECONDS);
            if (!acquired) {
                sendAckError(socketChannelContext, taskId, requestId, 42910, "server busy");
                return;
            }

            // 先 ACK 再发送窗口数据，客户端可据此构建本次 range 响应元信息
            sendAckSuccess(socketChannelContext, taskId, requestId, fileId, fileSize, startOffset, actualLength);
            long sentBytes = transferWindow(file, startOffset, actualLength, socketChannelContext);

            session.setLastRequestId(requestId);
            session.setCurrentWindowStart(startOffset);
            session.setCurrentWindowLength(actualLength);
            session.setWindowSentBytes(sentBytes);
            session.touch();
            long nextOffset = startOffset + sentBytes;
            log.info("=>【在线视频拉流】本次拉取范围最终实际完成传输的信息为: 请求Id:{}, 起始位置startOffset:{}, 窗口大小actualLength:{}, 本次实际发送字节数sentBytes:{}",
                requestId, startOffset, actualLength, sentBytes);
            // 窗口结束后发送 END，连接保持不关闭，等待下一次 META(range_pull)
            sendEndSuccess(socketChannelContext, taskId, requestId, nextOffset, sentBytes, nextOffset >= fileSize);
        } catch (IOException ex) {
            log.error("range_pull transfer failed: taskId={}, requestId={}, fileId={}", taskId, requestId, fileId, ex);
            sendEndError(socketChannelContext, taskId, requestId, 50031, "io read failed");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            sendEndError(socketChannelContext, taskId, requestId, 50032, "queue drain timeout");
        } finally {
            if (acquired) {
                STREAM_SEMAPHORE.release();
            }
        }
    }

    private RangePullSessionContext getOrCreateSession(String taskId,
                                                       String remoteAddress,
                                                       Long fileId,
                                                       long fileSize) {
        RangePullSessionContext current = sessionMap.get(taskId);
        if (current == null || !Objects.equals(current.getRemoteAddress(), remoteAddress)) {
            RangePullSessionContext created = new RangePullSessionContext();
            created.setTaskId(taskId);
            created.setRemoteAddress(remoteAddress);
            created.setFileId(fileId);
            created.setFileSize(fileSize);
            sessionMap.put(taskId, created);
            return created;
        }

        current.setFileId(fileId);
        current.setFileSize(fileSize);
        current.touch();
        return current;
    }

    private long transferWindow(File file,
                                long startOffset,
                                long length,
                                SocketChannelContext socketChannelContext) throws IOException, InterruptedException {
        // 只发送 [startOffset, startOffset + length) 的窗口，不越界到 EOF 之后
        long endExclusive = startOffset + length;
        long sent = 0;
        long lastProgressTs = System.currentTimeMillis();

        try (RandomAccessFile randomAccessFile = new RandomAccessFile(file, "r");
             FileChannel channel = randomAccessFile.getChannel()) {

            channel.position(startOffset);
            byte[] buffer = new byte[BUFFER_SIZE];
            ByteBuffer readBuffer = ByteBuffer.wrap(buffer);

            while (startOffset + sent < endExclusive) {
                // 写队列积压时暂停读盘，避免内存和队列持续膨胀
                applyBackpressure(socketChannelContext, lastProgressTs);

                int toRead = (int) Math.min(BUFFER_SIZE, endExclusive - (startOffset + sent));
                readBuffer.clear();
                readBuffer.limit(toRead);

                int n = channel.read(readBuffer);
                if (n < 0) {
                    break;
                }
                if (n == 0) {
                    continue;
                }

                // 按当前读到的有效字节构造 DATA_FRAME
                byte[] data = new byte[n];
                System.arraycopy(buffer, 0, data, 0, n);
                sendDataFrame(socketChannelContext, data);
                sent += n;
                lastProgressTs = System.currentTimeMillis();
                log.info("=>【在线视频拉流】本次拉取范围: [起始位置-最大位置]:[{}], 本范围内本次实际读取到且发送的有效字节数:{}",
                    startOffset+" - " + endExclusive, toRead);
            }
        }

        // 确保本窗口 DATA 尽量出队后再返回，避免 ACK/END 与 DATA 时序错位
        WriteQueueHelper.waitForQueueDrain(socketChannelContext, 10000);
        return sent;
    }

    private void applyBackpressure(SocketChannelContext socketChannelContext, long lastProgressTs)
            throws IOException, InterruptedException {
        while (socketChannelContext.getPendingWriteQueue().size() > MAX_PENDING_BUFFERS) {
            // 长时间无进展判定为窗口超时，交由上层转为 END(error)
            if (System.currentTimeMillis() - lastProgressTs > WINDOW_TIMEOUT_MS) {
                throw new IOException("queue drain timeout");
            }
            Thread.sleep(20);
        }
    }

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

    private void sendAckSuccess(SocketChannelContext socketChannelContext,
                                String taskId,
                                String requestId,
                                Long fileId,
                                long fileSize,
                                long startOffset,
                                long length) throws IOException {
        JSONObject ack = new JSONObject();
        ack.put("status", "ok");
        ack.put("taskId", taskId);
        ack.put("requestId", requestId);
        ack.put("fileId", fileId);
        ack.put("fileSize", fileSize);
        ack.put("startOffset", startOffset);
        ack.put("length", length);
        ack.put("chunkSize", BUFFER_SIZE);
        ack.put("eof", startOffset + length >= fileSize);
        sendFrame(socketChannelContext, FrameType.ACK_FRAME, ack.toJSONString());
    }

    private void sendAckError(SocketChannelContext socketChannelContext,
                              String taskId,
                              String requestId,
                              int code,
                              String message) throws IOException {
        JSONObject ack = new JSONObject();
        ack.put("status", "error");
        ack.put("taskId", taskId);
        ack.put("requestId", requestId);
        ack.put("code", code);
        ack.put("message", message);
        sendFrame(socketChannelContext, FrameType.ACK_FRAME, ack.toJSONString());
    }

    private void sendEndSuccess(SocketChannelContext socketChannelContext,
                                String taskId,
                                String requestId,
                                long nextOffset,
                                long sentBytes,
                                boolean eof) throws IOException {
        JSONObject end = new JSONObject();
        end.put("status", "success");
        end.put("taskId", taskId);
        end.put("requestId", requestId);
        end.put("sentBytes", sentBytes);
        end.put("nextOffset", nextOffset);
        end.put("eof", eof);
        sendFrame(socketChannelContext, FrameType.END_FRAME, end.toJSONString());
    }

    private void sendEndError(SocketChannelContext socketChannelContext,
                              String taskId,
                              String requestId,
                              int code,
                              String message) throws IOException {
        JSONObject end = new JSONObject();
        end.put("status", "error");
        end.put("taskId", taskId);
        end.put("requestId", requestId);
        end.put("code", code);
        end.put("message", message);
        sendFrame(socketChannelContext, FrameType.END_FRAME, end.toJSONString());
    }

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
    }

    public static void checkAndFreezeIdleTasks(long idleThreshold) {
        long now = System.currentTimeMillis();
        // 清理长时间无请求的 pull 会话，防止 sessionMap 无界增长
        sessionMap.entrySet().removeIf(entry -> now - entry.getValue().getLastActiveTime() > idleThreshold);
    }

    public static void cleanupConnection(String remoteAddress) {
        parserMap.remove(remoteAddress);
        sessionMap.entrySet().removeIf(entry -> remoteAddress.equals(entry.getValue().getRemoteAddress()));
    }
}
