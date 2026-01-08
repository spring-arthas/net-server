package com.alibaba.server.nio.client;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;

/**
 * 文件下载客户端 Demo
 * 基于自定义协议实现文件下载功能，处理 TCP 粘包/半包问题
 * 
 * 协议格式：
 * +--------+--------+--------+--------+--------+
 * | Magic | Type | Flags | Length | Data |
 * | 2字节 | 1字节 | 1字节 | 4字节 | N字节 |
 * +--------+--------+--------+--------+--------+
 * 
 * 流程：
 * 1. 发送下载请求（META_FRAME）
 * 2. 接收文件信息（META_FRAME）
 * 3. 发送确认（ACK_FRAME）
 * 4. 接收文件数据（DATA_FRAME）
 * 5. 接收完成通知（END_FRAME）
 * 
 * @author YSFY
 */
public class FileDownloadClient {

    // 魔数
    private static final byte[] MAGIC = { (byte) 0xFA, (byte) 0xCE };

    // 帧头长度
    private static final int HEADER_LENGTH = 8;

    // 帧类型
    private static final byte TYPE_META_FRAME = 0x01;
    private static final byte TYPE_DATA_FRAME = 0x02;
    private static final byte TYPE_END_FRAME = 0x03;
    private static final byte TYPE_ACK_FRAME = 0x04;

    // 服务端地址
    private static final String SERVER_HOST = "172.21.32.104";
    private static final int SERVER_PORT = 10088; // 下载端口

    // 缓冲区大小
    private static final int BUFFER_SIZE = 64 * 1024;

    public static void main(String[] args) {
        // 要下载的文件 ID
        Long fileId = 1L;
        // 保存目录
        String saveDir = "/Users/hljy/Downloads/";

        try {
            downloadFile(fileId, saveDir);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 下载文件
     */
    public static void downloadFile(Long fileId, String saveDir) throws Exception {
        System.out.println("========== 开始下载文件 ==========");
        System.out.println("文件ID: " + fileId);

        try (SocketChannel socketChannel = SocketChannel.open()) {
            // 1. 连接服务器
            socketChannel.connect(new InetSocketAddress(SERVER_HOST, SERVER_PORT));
            socketChannel.configureBlocking(true);
            System.out.println("已连接服务器: " + SERVER_HOST + ":" + SERVER_PORT);

            // 2. 发送下载请求
            sendDownloadRequest(socketChannel, fileId);

            // 3. 接收并处理服务端响应
            receiveAndSaveFile(socketChannel, saveDir);

        } catch (IOException e) {
            System.err.println("下载失败: " + e.getMessage());
            throw e;
        }
    }

    /**
     * 发送下载请求
     */
    private static void sendDownloadRequest(SocketChannel socketChannel, Long fileId) throws IOException {
        JSONObject request = new JSONObject();
        request.put("fileId", fileId);

        byte[] data = request.toJSONString().getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_LENGTH + data.length);

        buffer.put(MAGIC);
        buffer.put(TYPE_META_FRAME);
        buffer.put((byte) 0);
        buffer.putInt(data.length);
        buffer.put(data);
        buffer.flip();

        socketChannel.write(buffer);
        System.out.println("已发送下载请求: fileId=" + fileId);
    }

    /**
     * 接收并保存文件（处理粘包半包）
     */
    private static void receiveAndSaveFile(SocketChannel socketChannel, String saveDir) throws IOException {
        ByteBuffer readBuffer = ByteBuffer.allocate(BUFFER_SIZE);
        ByteBuffer accumulator = ByteBuffer.allocate(10 * 1024 * 1024); // 10MB 累积缓冲区

        FileChannel fileChannel = null;
        String fileName = null;
        long fileSize = 0;
        long bytesReceived = 0;
        boolean completed = false;

        while (!completed) {
            // 读取数据
            int bytesRead = socketChannel.read(readBuffer);
            if (bytesRead == -1) {
                System.out.println("服务端关闭连接");
                break;
            }

            readBuffer.flip();
            accumulator.put(readBuffer);
            readBuffer.clear();

            // 解析所有完整的帧
            accumulator.flip();
            while (accumulator.remaining() >= HEADER_LENGTH) {
                accumulator.mark(); // 标记当前位置
                byte magic1 = accumulator.get();
                byte magic2 = accumulator.get();

                if (magic1 != MAGIC[0] || magic2 != MAGIC[1]) {
                    System.err.printf("无效的魔数，关闭连接。期望: 0x%02X 0x%02X, 实际: 0x%02X 0x%02X%n",
                            MAGIC[0] & 0xFF, MAGIC[1] & 0xFF, magic1 & 0xFF, magic2 & 0xFF);
                    completed = true;
                    socketChannel.close();
                    break;
                }

                byte type = accumulator.get();
                byte flags = accumulator.get();
                int dataLength = accumulator.getInt();

                // 检查数据是否完整（半包处理）
                if (accumulator.remaining() < dataLength) {
                    // 数据不完整，回退位置，等待更多数据
                    accumulator.reset();
                    break;
                }

                // 读取帧数据
                byte[] frameData = new byte[dataLength];
                accumulator.get(frameData);

                // 处理帧
                switch (type) {
                    case TYPE_META_FRAME:
                        // 文件信息帧
                        JSONObject fileInfo = JSON.parseObject(new String(frameData, StandardCharsets.UTF_8));
                        fileName = fileInfo.getString("fileName");
                        fileSize = fileInfo.getLongValue("fileSize");
                        String filePath = fileInfo.getString("filePath");

                        System.out.println("收到文件信息: fileName=" + fileName + ", size=" + fileSize);

                        // 创建输出文件
                        File outputFile = new File(saveDir, fileName);
                        if (!outputFile.getParentFile().exists()) {
                            outputFile.getParentFile().mkdirs();
                        }
                        fileChannel = FileChannel.open(outputFile.toPath(),
                                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                                StandardOpenOption.TRUNCATE_EXISTING);

                        // 发送确认
                        sendAckFrame(socketChannel, "ready", filePath);
                        System.out.println("已发送准备确认");
                        break;

                    case TYPE_DATA_FRAME:
                        // 数据帧
                        if (fileChannel != null) {
                            ByteBuffer dataBuffer = ByteBuffer.wrap(frameData);
                            fileChannel.write(dataBuffer);
                            bytesReceived += frameData.length;

                            // 打印进度
                            if (fileSize > 0) {
                                double progress = (double) bytesReceived / fileSize * 100;
                                System.out.printf("\r下载进度: %.2f%% (%d/%d bytes)", progress, bytesReceived, fileSize);
                            }
                        }
                        break;

                    case TYPE_END_FRAME:
                        // 结束帧
                        JSONObject endInfo = JSON.parseObject(new String(frameData, StandardCharsets.UTF_8));
                        String status = endInfo.getString("status");

                        System.out.println("\n收到结束帧: status=" + status);

                        if ("success".equals(status)) {
                            System.out.println("========== 下载完成 ==========");
                            System.out.println("文件: " + saveDir + fileName);
                            System.out.println("大小: " + bytesReceived + " bytes");
                        }

                        completed = true;
                        break;

                    case TYPE_ACK_FRAME:
                        // 错误响应
                        JSONObject ack = JSON.parseObject(new String(frameData, StandardCharsets.UTF_8));
                        if ("error".equals(ack.getString("status"))) {
                            System.err.println("服务端错误: " + ack.getString("message"));
                            completed = true;
                        }
                        break;

                    default:
                        System.err.println("未知帧类型: " + type);
                        break;
                }
            }

            // 压缩缓冲区，保留未处理的数据
            accumulator.compact();
        }

        // 关闭文件通道
        if (fileChannel != null) {
            fileChannel.close();
        }
    }

    /**
     * 发送确认帧
     */
    private static void sendAckFrame(SocketChannel socketChannel, String status, String filePath) throws IOException {
        JSONObject ack = new JSONObject();
        ack.put("status", status);
        ack.put("filePath", filePath);

        byte[] data = ack.toJSONString().getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_LENGTH + data.length);

        buffer.put(MAGIC);
        buffer.put(TYPE_ACK_FRAME);
        buffer.put((byte) 0);
        buffer.putInt(data.length);
        buffer.put(data);
        buffer.flip();

        socketChannel.write(buffer);
    }
}
