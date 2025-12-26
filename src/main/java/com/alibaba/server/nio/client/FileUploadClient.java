package com.alibaba.server.nio.client;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * 文件上传客户端测试类
 * 基于自定义协议实现文件上传功能
 * 
 * 协议格式：
 * +--------+--------+--------+--------+--------+
 * | Magic | Type | Flags | Length | Data |
 * | 2字节 | 1字节 | 1字节 | 4字节 | N字节 |
 * +--------+--------+--------+--------+--------+
 * 
 * @author YSFY
 */
public class FileUploadClient {

    /**
     * 魔数
     */
    private static final byte[] MAGIC = { (byte) 0xFA, (byte) 0xCE };

    /**
     * 帧头长度
     */
    private static final int HEADER_LENGTH = 8;

    /**
     * 帧类型
     */
    private static final byte TYPE_META_FRAME = 0x01;
    private static final byte TYPE_DATA_FRAME = 0x02;
    private static final byte TYPE_END_FRAME = 0x03;
    private static final byte TYPE_ACK_FRAME = 0x04;

    /**
     * 数据分块大小：64KB
     */
    private static final int CHUNK_SIZE = 64 * 1024;

    /**
     * 服务端地址
     */
    private static final String SERVER_HOST = "172.21.32.104";
    private static final int SERVER_PORT = 10087;

    /**
     * 测试文件路径
     */
    private static final String TEST_FILE_PATH = "/Users/hljy/Downloads/中式美女在旗袍店试穿旗袍_小样.mov";

    public static void main(String[] args) {
        SocketChannel socketChannel = null;
        FileChannel fileChannel = null;

        try {
            Path filePath = Paths.get(TEST_FILE_PATH);

            // 1. 检查文件是否存在
            if (!Files.exists(filePath)) {
                System.err.println("文件不存在: " + TEST_FILE_PATH);
                return;
            }

            // 获取文件信息
            String fileName = filePath.getFileName().toString();
            long fileSize = Files.size(filePath);
            String fileType = getFileExtension(fileName);

            System.out.println("========== 文件上传客户端 ==========");
            System.out.println("文件名: " + fileName);
            System.out.println("文件大小: " + fileSize + " 字节 (" + (fileSize / 1024 / 1024) + " MB)");
            System.out.println("文件类型: " + fileType);
            System.out.println("服务器地址: " + SERVER_HOST + ":" + SERVER_PORT);
            System.out.println("====================================\n");

            // 2. 建立 SocketChannel 连接
            System.out.println("[1] 建立连接...");
            socketChannel = SocketChannel.open();
            socketChannel.configureBlocking(true); // 使用阻塞模式简化测试
            socketChannel.connect(new InetSocketAddress(SERVER_HOST, SERVER_PORT));
            System.out.println(
                    "    连接成功: " + socketChannel.getLocalAddress() + " -> " + socketChannel.getRemoteAddress());

            // 3. 发送元数据帧 (META_FRAME)
            System.out.println("\n[2] 发送元数据帧...");
            String taskId = sendMetaFrame(socketChannel, fileName, fileSize, fileType);
            System.out.println("    任务ID: " + taskId);

            // 4. 打开文件通道
            System.out.println("\n[3] 打开文件通道...");
            fileChannel = FileChannel.open(filePath, StandardOpenOption.READ);
            System.out.println("    文件通道已打开");

            // 5. 分块发送数据帧 (DATA_FRAME)
            System.out.println("\n[4] 发送文件数据...");
            long totalSent = sendDataFrames(socketChannel, fileChannel, fileSize);
            System.out.println("    发送完成，共发送 " + totalSent + " 字节");

            // 6. 关闭文件通道
            System.out.println("\n[5] 关闭文件通道...");
            fileChannel.close();
            fileChannel = null;
            System.out.println("    文件通道已关闭");

            // 7. 发送结束帧 (END_FRAME)
            System.out.println("\n[6] 发送结束帧...");
            sendEndFrame(socketChannel, taskId);
            System.out.println("    结束帧已发送");

            // 8. 等待服务端完成确认
            System.out.println("\n[7] 等待服务端确认...");
            // 可选：服务端会在完成后关闭连接

            System.out.println("\n========== 上传完成 ==========");

        } catch (Exception e) {
            System.err.println("上传失败: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // 9. 关闭资源
            System.out.println("\n[8] 关闭资源...");

            if (fileChannel != null) {
                try {
                    fileChannel.close();
                    System.out.println("    文件通道已关闭");
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            if (socketChannel != null) {
                try {
                    socketChannel.close();
                    System.out.println("    SocketChannel 已关闭");
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * 发送元数据帧 (META_FRAME)
     * 
     * @return taskId（从服务端 ACK 响应中获取）
     */
    private static String sendMetaFrame(SocketChannel socketChannel,
            String fileName, long fileSize, String fileType) throws IOException {

        // 构建元数据 JSON
        JSONObject metaJson = new JSONObject();
        metaJson.put("fileName", fileName);
        metaJson.put("fileSize", fileSize);
        metaJson.put("fileType", fileType);

        byte[] metaData = metaJson.toJSONString().getBytes(StandardCharsets.UTF_8);

        // 构建帧
        ByteBuffer buffer = buildFrame(TYPE_META_FRAME, (byte) 0, metaData);

        // 发送
        socketChannel.write(buffer);
        System.out.println("    元数据帧已发送: " + metaJson.toJSONString());

        // 接收 ACK 响应
        String taskId = receiveAck(socketChannel);
        return taskId;
    }

    /**
     * 分块发送数据帧 (DATA_FRAME)
     * 
     * @return 发送的总字节数
     */
    private static long sendDataFrames(SocketChannel socketChannel,
            FileChannel fileChannel, long fileSize) throws IOException {

        ByteBuffer readBuffer = ByteBuffer.allocate(CHUNK_SIZE);
        long totalSent = 0;
        int chunkIndex = 0;

        while (totalSent < fileSize) {
            // 从文件读取数据
            readBuffer.clear();
            int bytesRead = fileChannel.read(readBuffer);
            if (bytesRead == -1) {
                break;
            }
            readBuffer.flip();

            // 提取实际读取的数据
            byte[] chunkData = new byte[bytesRead];
            readBuffer.get(chunkData);

            // 构建数据帧
            ByteBuffer frameBuffer = buildFrame(TYPE_DATA_FRAME, (byte) 0, chunkData);

            // 发送
            socketChannel.write(frameBuffer);
            totalSent += bytesRead;
            chunkIndex++;

            // 进度显示
            double progress = (totalSent * 100.0) / fileSize;
            System.out.printf("    进度: %.2f%% (%d/%d 字节, 块 #%d)\r",
                    progress, totalSent, fileSize, chunkIndex);
        }

        System.out.println(); // 换行
        return totalSent;
    }

    /**
     * 发送结束帧 (END_FRAME)
     */
    private static void sendEndFrame(SocketChannel socketChannel, String taskId) throws IOException {
        // 构建结束帧数据 JSON
        JSONObject endJson = new JSONObject();
        endJson.put("taskId", taskId);
        // 可选：添加校验和
        // endJson.put("checksum", calculateChecksum());

        byte[] endData = endJson.toJSONString().getBytes(StandardCharsets.UTF_8);

        // 构建帧（设置 FLAG_LAST_FRAME 标志）
        ByteBuffer buffer = buildFrame(TYPE_END_FRAME, (byte) 0x01, endData);

        // 发送
        socketChannel.write(buffer);
    }

    /**
     * 接收 ACK 响应
     * 
     * @return taskId
     */
    private static String receiveAck(SocketChannel socketChannel) throws IOException {
        // 读取帧头
        ByteBuffer headerBuffer = ByteBuffer.allocate(HEADER_LENGTH);
        int bytesRead = 0;
        while (bytesRead < HEADER_LENGTH) {
            int read = socketChannel.read(headerBuffer);
            if (read == -1) {
                throw new IOException("连接已关闭");
            }
            bytesRead += read;
        }
        headerBuffer.flip();

        // 验证魔数
        if (headerBuffer.get() != MAGIC[0] || headerBuffer.get() != MAGIC[1]) {
            throw new IOException("无效的魔数");
        }

        // 解析帧类型
        byte type = headerBuffer.get();
        if (type != TYPE_ACK_FRAME) {
            throw new IOException("期望 ACK 帧，收到类型: " + type);
        }

        // 跳过标志位
        headerBuffer.get();

        // 解析数据长度
        int dataLength = headerBuffer.getInt();

        // 读取数据
        ByteBuffer dataBuffer = ByteBuffer.allocate(dataLength);
        bytesRead = 0;
        while (bytesRead < dataLength) {
            int read = socketChannel.read(dataBuffer);
            if (read == -1) {
                throw new IOException("连接已关闭");
            }
            bytesRead += read;
        }
        dataBuffer.flip();

        byte[] data = new byte[dataLength];
        dataBuffer.get(data);

        // 解析 ACK JSON
        String ackJson = new String(data, StandardCharsets.UTF_8);
        System.out.println("    收到 ACK: " + ackJson);

        JSONObject ack = JSON.parseObject(ackJson);
        String status = ack.getString("status");
        if (!"ready".equals(status) && !"success".equals(status)) {
            String message = ack.getString("message");
            throw new IOException("服务端返回错误: " + status + " - " + message);
        }

        return ack.getString("taskId");
    }

    /**
     * 构建协议帧
     */
    private static ByteBuffer buildFrame(byte type, byte flags, byte[] data) {
        int frameLength = HEADER_LENGTH + (data != null ? data.length : 0);
        ByteBuffer buffer = ByteBuffer.allocate(frameLength);

        // 魔数 (2字节)
        buffer.put(MAGIC);

        // 帧类型 (1字节)
        buffer.put(type);

        // 标志位 (1字节)
        buffer.put(flags);

        // 数据长度 (4字节，大端序)
        buffer.putInt(data != null ? data.length : 0);

        // 数据
        if (data != null && data.length > 0) {
            buffer.put(data);
        }

        buffer.flip();
        return buffer;
    }

    /**
     * 获取文件扩展名
     */
    private static String getFileExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0 && dotIndex < fileName.length() - 1) {
            return fileName.substring(dotIndex + 1).toLowerCase();
        }
        return "";
    }
}
