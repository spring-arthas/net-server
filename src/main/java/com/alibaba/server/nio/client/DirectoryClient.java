package com.alibaba.server.nio.client;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.server.nio.model.file.FileUploadFrame;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

/**
 * 目录操作客户端Demo
 * 
 * 用于测试目录的创建、删除、更新、移动功能
 * 
 * @author spring
 */
public class DirectoryClient {

    private static final String SERVER_HOST = "172.21.32.104";
    private static final int SERVER_PORT = 10086; // TEXT传输端口

    private static final byte[] MAGIC = { (byte) 0xFA, (byte) 0xCE };
    private static final int HEADER_LENGTH = 8;

    // 帧类型
    private static final byte DIR_CREATE_REQ = 0x10;
    private static final byte DIR_DELETE_REQ = 0x11;
    private static final byte DIR_UPDATE_REQ = 0x12;
    private static final byte DIR_MOVE_REQ = 0x13;
    private static final byte DIR_RESPONSE = 0x14;

    // 顶层目录ID
    private static final long ROOT_DIR_ID = 7408085068658278401L;

    public static void main(String[] args) {
        DirectoryClient client = new DirectoryClient();

        try {
            // 测试1: 创建目录
            System.out.println("=== 测试1: 创建目录 ===");
            client.testCreateDirectory("测试目录", ROOT_DIR_ID);

            // 测试2: 创建目录-名称过长(预期失败)
            System.out.println("\n=== 测试2: 创建目录(名称过长) ===");
            client.testCreateDirectory("这是一个超长名称", ROOT_DIR_ID);

            // 测试3: 删除目录（需要替换为实际的目录ID）
            // 注意：请将下面的 dirId 替换为测试1创建成功后返回的目录ID
            System.out.println("\n=== 测试3: 删除目录 ===");
            long dirIdToDelete = 0L; // TODO: 替换为实际创建的目录ID
            if (dirIdToDelete > 0) {
                client.testDeleteDirectory(dirIdToDelete);
            } else {
                System.out.println("请先设置要删除的目录ID (dirIdToDelete)");
            }

            // 测试4: 删除有子项的目录（预期失败）
            System.out.println("\n=== 测试4: 删除顶层目录(预期失败-有子项) ===");
            client.testDeleteDirectory(ROOT_DIR_ID);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 测试创建目录
     */
    public void testCreateDirectory(String dirName, long parentId) throws IOException {
        SocketChannel channel = null;
        try {
            channel = SocketChannel.open();
            channel.connect(new InetSocketAddress(SERVER_HOST, SERVER_PORT));
            channel.configureBlocking(true);
            System.out.println("已连接到服务器: " + SERVER_HOST + ":" + SERVER_PORT);

            // 构建请求
            JSONObject request = new JSONObject();
            request.put("action", "CREATE");
            request.put("parentId", parentId);
            request.put("dirName", dirName);

            // 发送请求帧
            sendFrame(channel, DIR_CREATE_REQ, request.toJSONString());
            System.out.println("发送创建目录请求: " + request.toJSONString());

            // 接收响应
            JSONObject response = receiveResponse(channel);
            System.out.println("收到响应: " + response.toJSONString());

        } finally {
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
        }
    }

    /**
     * 测试删除目录
     */
    public void testDeleteDirectory(long dirId) throws IOException {
        SocketChannel channel = null;
        try {
            channel = SocketChannel.open();
            channel.connect(new InetSocketAddress(SERVER_HOST, SERVER_PORT));
            channel.configureBlocking(true);

            JSONObject request = new JSONObject();
            request.put("action", "DELETE");
            request.put("dirId", dirId);

            sendFrame(channel, DIR_DELETE_REQ, request.toJSONString());
            System.out.println("发送删除目录请求: " + request.toJSONString());

            JSONObject response = receiveResponse(channel);
            System.out.println("收到响应: " + response.toJSONString());

        } finally {
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
        }
    }

    /**
     * 测试更新目录名称
     */
    public void testUpdateDirectory(long dirId, String newName) throws IOException {
        SocketChannel channel = null;
        try {
            channel = SocketChannel.open();
            channel.connect(new InetSocketAddress(SERVER_HOST, SERVER_PORT));
            channel.configureBlocking(true);

            JSONObject request = new JSONObject();
            request.put("action", "UPDATE");
            request.put("dirId", dirId);
            request.put("newName", newName);

            sendFrame(channel, DIR_UPDATE_REQ, request.toJSONString());
            System.out.println("发送更新目录请求: " + request.toJSONString());

            JSONObject response = receiveResponse(channel);
            System.out.println("收到响应: " + response.toJSONString());

        } finally {
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
        }
    }

    /**
     * 测试移动目录
     */
    public void testMoveDirectory(long dirId, long targetParentId) throws IOException {
        SocketChannel channel = null;
        try {
            channel = SocketChannel.open();
            channel.connect(new InetSocketAddress(SERVER_HOST, SERVER_PORT));
            channel.configureBlocking(true);

            JSONObject request = new JSONObject();
            request.put("action", "MOVE");
            request.put("dirId", dirId);
            request.put("targetParentId", targetParentId);

            sendFrame(channel, DIR_MOVE_REQ, request.toJSONString());
            System.out.println("发送移动目录请求: " + request.toJSONString());

            JSONObject response = receiveResponse(channel);
            System.out.println("收到响应: " + response.toJSONString());

        } finally {
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
        }
    }

    /**
     * 发送帧
     */
    private void sendFrame(SocketChannel channel, byte frameType, String jsonData) throws IOException {
        byte[] data = jsonData.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_LENGTH + data.length);

        buffer.put(MAGIC);
        buffer.put(frameType);
        buffer.put((byte) 0); // flags
        buffer.putInt(data.length);
        buffer.put(data);
        buffer.flip();

        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    /**
     * 接收响应
     */
    private JSONObject receiveResponse(SocketChannel channel) throws IOException {
        // 读取帧头
        ByteBuffer headerBuffer = ByteBuffer.allocate(HEADER_LENGTH);
        while (headerBuffer.hasRemaining()) {
            int read = channel.read(headerBuffer);
            if (read == -1) {
                throw new IOException("连接已关闭");
            }
        }
        headerBuffer.flip();

        // 验证魔数
        byte magic1 = headerBuffer.get();
        byte magic2 = headerBuffer.get();
        if (magic1 != MAGIC[0] || magic2 != MAGIC[1]) {
            throw new IOException("无效的魔数");
        }

        // 读取帧类型
        byte type = headerBuffer.get();
        byte flags = headerBuffer.get();
        int dataLength = headerBuffer.getInt();

        // 读取数据
        ByteBuffer dataBuffer = ByteBuffer.allocate(dataLength);
        while (dataBuffer.hasRemaining()) {
            int read = channel.read(dataBuffer);
            if (read == -1) {
                throw new IOException("连接已关闭");
            }
        }
        dataBuffer.flip();

        String jsonStr = new String(dataBuffer.array(), 0, dataLength, StandardCharsets.UTF_8);
        return JSON.parseObject(jsonStr);
    }
}
