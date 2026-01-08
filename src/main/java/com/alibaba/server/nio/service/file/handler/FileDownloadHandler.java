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
import com.alibaba.server.nio.model.file.FileUploadFrame;
import com.alibaba.server.nio.model.file.FileUploadFrame.FrameType;
import com.alibaba.server.nio.repository.file.service.FileService;
import com.alibaba.server.nio.repository.file.service.dto.FileDto;
import com.alibaba.server.nio.repository.file.service.param.FileQueryParam;
import com.alibaba.server.nio.service.api.AbstractChannelHandler;
import com.alibaba.server.nio.service.file.parser.FileUploadFrameParser;
import com.alibaba.server.util.LocalTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
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
     * 帧解析器缓存：remoteAddress -> FileUploadFrameParser
     */
    private static final ConcurrentHashMap<String, FileUploadFrameParser> parserMap = new ConcurrentHashMap<>();

    /**
     * 下载状态缓存：remoteAddress -> 是否正在下载
     */
    private static final ConcurrentHashMap<String, Boolean> downloadingMap = new ConcurrentHashMap<>();

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
        FileUploadFrameParser parser = getOrCreateParser(socketChannelContext);

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
    private FileUploadFrameParser getOrCreateParser(SocketChannelContext socketChannelContext) {
        String remoteAddress = socketChannelContext.getRemoteAddress();
        return parserMap.computeIfAbsent(remoteAddress, k -> {
            log.debug("为下载通道 {} 创建新的 FileUploadFrameParser", remoteAddress);
            return new FileUploadFrameParser();
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

        log.debug("处理下载请求帧: type={}, dataLength={}", frame.getType(), frame.getDataLength());

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
    private void handleDownloadRequest(FileUploadFrame frame,
            SocketChannelContext socketChannelContext,
            SimpleChannelContext simpleChannelContext) throws IOException {
        try {
            // 1. 解析请求 JSON
            String jsonData = frame.getDataAsString();
            JSONObject request = JSON.parseObject(jsonData);

            Long fileId = request.getLong("fileId");
            String filePath = request.getString("filePath");

            log.info("[ {} ] FileDownloadHandler | --> 接收到下载请求: fileId={}, filePath={}",
                    LocalTime.formatDate(LocalDateTime.now()), fileId, filePath);

            // 2. 从 DB 校验文件记录
            FileService fileService = BasicServer.classPathXmlApplicationContext.getBean(FileService.class);
            FileQueryParam queryParam = new FileQueryParam();
            queryParam.setId(fileId);
            FileDto fileDto = fileService.getFileById(queryParam);

            if (fileDto == null || fileDto.getId() == null) {
                log.warn("文件记录不存在: fileId={}", fileId);
                sendErrorFrame(socketChannelContext, "文件不存在");
                closeConnection(socketChannelContext, simpleChannelContext);
                return;
            }

            // 3. 校验文件系统中文件是否存在
            File file = new File(fileDto.getFilePath());
            if (!file.exists() || !file.isFile()) {
                log.warn("文件系统中文件不存在: path={}", fileDto.getFilePath());
                sendErrorFrame(socketChannelContext, "文件不存在");
                closeConnection(socketChannelContext, simpleChannelContext);
                return;
            }

            // 4. 发送文件信息给客户端
            JSONObject fileInfo = new JSONObject();
            fileInfo.put("fileId", fileDto.getId());
            fileInfo.put("fileName", fileDto.getFileName());
            fileInfo.put("fileSize", file.length());
            fileInfo.put("fileType", fileDto.getFileType());
            fileInfo.put("filePath", fileDto.getFilePath());

            sendFrame(socketChannelContext, FrameType.META_FRAME, fileInfo.toJSONString());

            log.info("[ {} ] FileDownloadHandler | --> 已发送文件信息，等待客户端确认: fileName={}, size={}",
                    LocalTime.formatDate(LocalDateTime.now()), fileDto.getFileName(), file.length());

        } catch (Exception e) {
            log.error("处理下载请求失败", e);
            sendErrorFrame(socketChannelContext, "处理请求失败: " + e.getMessage());
        }
    }

    /**
     * 处理客户端确认（ACK_FRAME）
     * 开始传输文件流
     */
    private void handleClientAck(FileUploadFrame frame,
            SocketChannelContext socketChannelContext,
            SimpleChannelContext simpleChannelContext) throws IOException {
        try {
            String jsonData = frame.getDataAsString();
            JSONObject ack = JSON.parseObject(jsonData);

            String status = ack.getString("status");
            String filePath = ack.getString("filePath");

            if (!"ready".equals(status)) {
                log.warn("客户端未准备好，关闭连接");
                closeConnection(socketChannelContext, simpleChannelContext);
                return;
            }

            log.info("[ {} ] FileDownloadHandler | --> 客户端已准备好，开始传输文件: path={}",
                    LocalTime.formatDate(LocalDateTime.now()), filePath);

            // 开始传输文件
            transferFile(socketChannelContext, simpleChannelContext, filePath);

        } catch (Exception e) {
            log.error("处理客户端确认失败", e);
            sendErrorFrame(socketChannelContext, "传输失败: " + e.getMessage());
        }
    }

    /**
     * 传输文件流
     */
    private void transferFile(SocketChannelContext socketChannelContext,
            SimpleChannelContext simpleChannelContext,
            String filePath) throws IOException {
        File file = new File(filePath);
        if (!file.exists()) {
            sendErrorFrame(socketChannelContext, "文件不存在");
            return;
        }

        long startTime = System.currentTimeMillis();
        long totalBytes = 0;

        try (FileInputStream fis = new FileInputStream(file);
                FileChannel fileChannel = fis.getChannel()) {

            // 分块读取并发送 - 使用直接缓冲区减少内存拷贝
            int bufferSize = 131072; // 128KB
            ByteBuffer readBuffer = ByteBuffer.allocateDirect(bufferSize);

            while (fileChannel.read(readBuffer) != -1) {
                readBuffer.flip();

                // 构建数据帧
                byte[] data = new byte[readBuffer.remaining()];
                readBuffer.get(data);
                sendDataFrame(socketChannelContext, data);
                totalBytes += data.length;

                readBuffer.clear();
            }

            // 等待所有 DATA_FRAME 发送完成（无限等待，但如果30秒无进展则认为卡死）
            log.info("等待所有数据帧发送完成...");
            WriteQueueHelper.waitForQueueDrain(socketChannelContext, 30000); // 30秒无进展超时

            // 发送结束帧
            JSONObject endJson = new JSONObject();
            endJson.put("status", "success");
            endJson.put("totalBytes", totalBytes);
            sendFrame(socketChannelContext, FrameType.END_FRAME, endJson.toJSONString());

            // 等待 END_FRAME 发送完成
            WriteQueueHelper.waitForQueueDrain(socketChannelContext, 10000); // 10秒无进展超时

            long duration = System.currentTimeMillis() - startTime;
            log.info("[ {} ] FileDownloadHandler | --> 文件传输完成: fileName={}, size={}, 耗时={}ms",
                    LocalTime.formatDate(LocalDateTime.now()),
                    file.getName(), totalBytes, duration);

            // 关闭连接
            closeConnection(socketChannelContext, simpleChannelContext);

        } catch (Exception e) {
            log.error("文件传输失败: path={}", filePath, e);
            sendErrorFrame(socketChannelContext, "传输失败: " + e.getMessage());
        }
    }

    /**
     * 发送数据帧 - 使用直接缓冲区优化性能
     */
    private void sendDataFrame(SocketChannelContext socketChannelContext, byte[] data) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocateDirect(FileUploadFrame.HEADER_LENGTH + data.length);
        buffer.put(FileUploadFrame.MAGIC);
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

        ByteBuffer buffer = ByteBuffer.allocate(FileUploadFrame.HEADER_LENGTH + data.length);
        buffer.put(FileUploadFrame.MAGIC);
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
            parserMap.remove(socketChannelContext.getRemoteAddress());
            downloadingMap.remove(socketChannelContext.getRemoteAddress());
            NioServerContext.closedAndRelease(socketChannelContext.getSocketChannel());
            simpleChannelContext.setNeedStop(true);
        } catch (Exception e) {
            log.error("关闭连接失败", e);
        }
    }

    /**
     * 清理指定连接的资源
     */
    public static void cleanupConnection(String remoteAddress) {
        parserMap.remove(remoteAddress);
        downloadingMap.remove(remoteAddress);
    }
}
