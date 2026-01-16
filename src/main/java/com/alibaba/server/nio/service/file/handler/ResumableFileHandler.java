package com.alibaba.server.nio.service.file.handler;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.server.common.BasicConstant;
import com.alibaba.server.common.OSinfo;
import com.alibaba.server.nio.core.server.NioServerContext;
import com.alibaba.server.nio.handler.pipe.ChannelContext;
import com.alibaba.server.nio.handler.pipe.standard.SimpleChannelContext;
import com.alibaba.server.nio.model.ChannelEventModel;
import com.alibaba.server.nio.model.SocketChannelContext;
import com.alibaba.server.nio.model.TransportDataModel;
import com.alibaba.server.nio.model.file.FileUploadContext;
import com.alibaba.server.nio.model.file.FileUploadFrame;
import com.alibaba.server.nio.model.resumable.ResumableContext;
import com.alibaba.server.nio.model.resumable.ResumableFrame;
import com.alibaba.server.nio.service.api.AbstractChannelHandler;
import com.alibaba.server.nio.service.file.parser.FrameParser;
import com.alibaba.server.nio.service.resumable.ResumableFrameParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.alibaba.server.nio.model.resumable.ResumableFrame.FrameType.*;

@Slf4j
public class ResumableFileHandler extends AbstractChannelHandler {

    private static final ConcurrentHashMap<String, ResumableContext> contextMap = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ResumableFrameParser> parserMap = new ConcurrentHashMap<>();

    @Override
    public void handler(Object o, ChannelContext channelContext) throws IOException {
        TransportDataModel transportDataModel = (TransportDataModel) o;
        SimpleChannelContext simpleChannelContext = (SimpleChannelContext) channelContext;
        SocketChannelContext socketChannelContext = simpleChannelContext.getSocketChannelContext();

        // 允许 RESUMABLE 或者 UPLOAD (兼容 MainFileUploadAcceptor)
        if (socketChannelContext == null) {
            return;
        }
        String handlerType = socketChannelContext.getHandlerType();
        if (!"RESUME_UPLOAD".equals(handlerType)) {
            return;
        }

        List<ChannelEventModel.GroupData> waitHandleDataList = transportDataModel.getWaitHandleDataList();
        if (CollectionUtils.isEmpty(waitHandleDataList)) {
            return;
        }

        ResumableFrameParser parser = getOrCreateParser(socketChannelContext);

        // 逐个处理待处理数据
        for (ChannelEventModel.GroupData groupData : waitHandleDataList) {
            List<ResumableFrame> frames = parser.parse(groupData.getBytes());

            // 处理解析完成的帧
            for (ResumableFrame frame : frames) {
                processFrame(frame, socketChannelContext, simpleChannelContext);
            }
        }
    }

    /**
     * 获取或创建帧解析器
     */
    private ResumableFrameParser getOrCreateParser(SocketChannelContext socketChannelContext) {
        String remoteAddress = socketChannelContext.getRemoteAddress();
        return parserMap.computeIfAbsent(remoteAddress, k -> {
            log.debug("为通道 {} 创建新的 ResumableFrameParser", remoteAddress);
            return new ResumableFrameParser();
        });
    }

    private void processFrame(ResumableFrame frame,
                              SocketChannelContext ctx,
                              SimpleChannelContext simpleChannelContext) throws IOException {

        if (null == frame.getType()) {
            log.warn("帧类型为空，跳过处理");
            return;
        }

        log.debug("处理帧: type={}, dataLength={}", frame.getType(), frame.getLength());

        switch (frame.getType()) {
            case TYPE_UPLOAD_CHECK:
                handleUploadCheck(frame, ctx);
                break;
            case TYPE_UPLOAD_DATA:
                handleUploadData(frame, ctx);
                break;
            case TYPE_UPLOAD_END:
                handleUploadEnd(frame, ctx);
                break;
            case TYPE_DOWNLOAD_CHECK:
                handleDownloadCheck(frame, ctx);
                break;
            default:
                // 这里的 default 实际上不会走到，因为 isResumableFrame 已经过滤了
                // log.warn("Unknown frame type: {}", frame.getType());
        }
    }

    private void handleUploadCheck(ResumableFrame frame, SocketChannelContext ctx) throws IOException {
        JSONObject json = JSON.parseObject(frame.getDataAsString());
        String md5 = json.getString("md5");
        String fileName = json.getString("fileName");
        long fileSize = json.getLongValue("fileSize");

        String basePath = "/Users/hljy/Downloads/西班牙的荷包蛋/2026/01/15/";
        
        String filePath = basePath + File.separator + "resumable" + File.separator + fileName; // 简化路径策略

        File file = new File(filePath);
        long currentSize = 0;
        if (file.exists()) {
            currentSize = file.length();
        }

        ResumableContext context = new ResumableContext();
        context.setTaskId(UUID.randomUUID().toString());
        context.setFileName(fileName);
        context.setMd5(md5);
        context.setFileSize(fileSize);
        context.setFilePath(filePath);
        context.setRemoteAddress(ctx.getRemoteAddress());
        
        context.openUploadChannel(); // 打开文件通道，准备写入
        
        contextMap.put(ctx.getRemoteAddress(), context);

        // 发送 ACK
        JSONObject response = new JSONObject();
        response.put("status", "resume");
        response.put("offset", currentSize);
        response.put("taskId", context.getTaskId());
        
        sendFrame(ctx, TYPE_UPLOAD_ACK, response.toJSONString().getBytes(StandardCharsets.UTF_8));
    }

    private ResumableContext getActiveUploadContext(SocketChannelContext socketChannelContext) {
        String remoteAddress = socketChannelContext.getRemoteAddress();
        // 根据远程地址精确匹配上传上下文，确保数据写入正确的文件
        for (ResumableContext ctx : contextMap.values()) {
            // 同时检查状态和远程地址，避免多客户端并发上传时数据写入错误文件
            if (ctx.getStatus() == ResumableContext.UploadStatus.UPLOADING
                    && remoteAddress != null
                    && remoteAddress.equals(ctx.getRemoteAddress())) {
                return ctx;
            }
        }
        return null;
    }

    private void handleUploadData(ResumableFrame frame, SocketChannelContext ctx) throws IOException {
        ResumableContext context = getActiveUploadContext(ctx);
        if (context == null) {
            return;
        }

        // 写入文件数据
        byte[] fileData = frame.getData();
        if (fileData != null && fileData.length > 0) {
            context.writeData(fileData);

            // 可选：每写入一定量数据后记录进度
            if (context.getBytesWritten() % (1024 * 1024) == 0) { // 每 1MB 记录一次
                log.debug("上传进度: taskId={}, progress={:.2f}%",
                        context.getTaskId(), context.getProgress());
            }
            context.setCurrentOffset(context.getCurrentOffset() + frame.getData().length);
        }
        // 可以在这里发送进度 ACK，或者客户端自己计数
    }

    private void handleUploadEnd(ResumableFrame frame, SocketChannelContext ctx) throws IOException {
        ResumableContext context = contextMap.remove(ctx.getRemoteAddress());
        if (context != null) {
            context.closeFileChannel();
            
            JSONObject response = new JSONObject();
            response.put("status", "success");
            sendFrame(ctx, TYPE_UPLOAD_ACK, response.toJSONString().getBytes(StandardCharsets.UTF_8));
            log.info("File upload completed: {}", context.getFileName());
        }
    }

    private void handleDownloadCheck(ResumableFrame frame, SocketChannelContext ctx) throws IOException {
        JSONObject json = JSON.parseObject(frame.getDataAsString());
        String fileName = json.getString("fileName"); // 简化：直接传文件名
        long startOffset = json.getLongValue("startOffset");

        String basePath = OSinfo.isWindows() ? 
                NioServerContext.getValue(BasicConstant.NIO_FILE_BASE_PATH_WINDOWS) :
                NioServerContext.getValue(BasicConstant.NIO_FILE_BASE_PATH_LINUX_MAC);
        String filePath = basePath + File.separator + "resumable" + File.separator + fileName;

        File file = new File(filePath);
        if (!file.exists()) {
             JSONObject response = new JSONObject();
             response.put("status", "error");
             response.put("message", "File not found");
             sendFrame(ctx, TYPE_DOWNLOAD_ACK, response.toJSONString().getBytes(StandardCharsets.UTF_8));
             return;
        }

        ResumableContext context = new ResumableContext();
        context.setFilePath(filePath);
        context.setStartOffset(startOffset);
        context.openDownloadChannel();
        
        // 发送 ACK 确认文件大小
        JSONObject response = new JSONObject();
        response.put("status", "ready");
        response.put("fileSize", file.length());
        sendFrame(ctx, TYPE_DOWNLOAD_ACK, response.toJSONString().getBytes(StandardCharsets.UTF_8));
        
        // 开始发送数据
        sendDownloadData(ctx, context);
    }

    private void sendDownloadData(SocketChannelContext ctx, ResumableContext context) {
        // 在新线程或当前线程循环发送（注意：长时间占用 NIO 线程是不好的，这里为了简化演示，分块发送）
        // 更好的做法是注册 Write 事件，或者放入 WriteQueue
        try {
            ByteBuffer buffer = ByteBuffer.allocate(32 * 1024); // 32KB chunks
            while (context.getFileChannel().read(buffer) != -1) {
                buffer.flip();
                byte[] data = new byte[buffer.remaining()];
                buffer.get(data);
                
                sendFrame(ctx, TYPE_DOWNLOAD_DATA, data);
                buffer.clear();
                
                // 简单的流控，避免淹没客户端
                try { Thread.sleep(1); } catch (InterruptedException e) {}
            }
            
            // 发送结束帧
            sendFrame(ctx, TYPE_DOWNLOAD_END, new byte[0]);
            context.closeFileChannel();
            
        } catch (IOException e) {
            log.error("Download error", e);
            context.closeFileChannel();
        }
    }

    private void sendFrame(SocketChannelContext ctx, ResumableFrame.FrameType type, byte[] data) throws IOException {
        int length = (data == null) ? 0 : data.length;
        ByteBuffer buffer = ByteBuffer.allocate(ResumableFrame.HEADER_LENGTH + length);
        buffer.put(ResumableFrame.MAGIC);
        buffer.put((byte) type.getCode());
        buffer.put((byte) 0); // flags
        buffer.putInt(length);
        if (data != null) {
            buffer.put(data);
        }
        buffer.flip();
        
        // 使用 WriteQueueHelper 发送
        com.alibaba.server.nio.handler.event.concret.WriteQueueHelper.submitWrite(ctx, buffer);
    }
}
