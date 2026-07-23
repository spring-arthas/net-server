package com.alibaba.server.nio.client;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

/**
 * 用户认证客户端Demo
 * 
 * 用于测试用户注册、登录、修改密码、退出登录功能
 * 
 * @author spring
 */
public class UserAuthClient {

    private static final String SERVER_HOST = "172.21.32.104";
    private static final int SERVER_PORT = 10086; // TEXT传输端口

    private static final byte[] MAGIC = { (byte) 0xFA, (byte) 0xCE };
    private static final int HEADER_LENGTH = 8;

    // 帧类型
    private static final byte USER_REGISTER_REQ = 0x30;
    private static final byte USER_LOGIN_REQ = 0x31;
    private static final byte USER_CHANGE_PWD_REQ = 0x32;
    private static final byte USER_LOGOUT_REQ = 0x33;
    private static final byte USER_RESPONSE = 0x34;

    private SocketChannel channel;

    public static void main(String[] args) {
        UserAuthClient client = new UserAuthClient();

        try {
            // 建立连接（复用）
            client.connect();

            // 测试1: 注册用户
            System.out.println("=== 测试1: 注册用户 ===");
            client.testRegister("test1", "123456");

            // 测试2: 注册用户-用户名过长(预期失败)
            System.out.println("\n=== 测试2: 注册用户(用户名过长) ===");
            client.testRegister("这是一个超长用户名", "123456");

            // 测试3: 登录
            System.out.println("\n=== 测试3: 登录 ===");
            client.testLogin("test1", "123456");

            // 测试4: 登录-密码错误(预期失败)
            System.out.println("\n=== 测试4: 登录(密码错误) ===");
            client.testLogin("test1", "wrongpwd");

            // 测试5: 修改密码
            System.out.println("\n=== 测试5: 修改密码 ===");
            client.testChangePassword("123456", "654321");

            // 测试6: 退出登录
            System.out.println("\n=== 测试6: 退出登录 ===");
            client.testLogout();

            // 关闭连接
            client.disconnect();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 建立连接
     */
    public void connect() throws IOException {
        channel = SocketChannel.open();
        channel.connect(new InetSocketAddress(SERVER_HOST, SERVER_PORT));
        channel.configureBlocking(true);
        System.out.println("已连接到服务器: " + SERVER_HOST + ":" + SERVER_PORT);
    }

    /**
     * 断开连接
     */
    public void disconnect() throws IOException {
        if (channel != null && channel.isOpen()) {
            channel.close();
            System.out.println("已断开连接");
        }
    }

    /**
     * 测试注册
     */
    public void testRegister(String userName, String password) throws IOException {
        JSONObject request = new JSONObject();
        request.put("userName", userName);
        request.put("password", password);

        sendFrame(USER_REGISTER_REQ, request.toJSONString());
        System.out.println("发送注册请求: " + request.toJSONString());

        JSONObject response = receiveResponse();
        System.out.println("收到响应: " + response.toJSONString());
    }

    /**
     * 测试登录
     */
    public void testLogin(String userName, String password) throws IOException {
        JSONObject request = new JSONObject();
        request.put("userName", userName);
        request.put("password", password);

        sendFrame(USER_LOGIN_REQ, request.toJSONString());
        System.out.println("发送登录请求: " + request.toJSONString());

        JSONObject response = receiveResponse();
        System.out.println("收到响应: " + response.toJSONString());
    }

    /**
     * 测试修改密码
     */
    public void testChangePassword(String oldPassword, String newPassword) throws IOException {
        JSONObject request = new JSONObject();
        request.put("oldPassword", oldPassword);
        request.put("newPassword", newPassword);

        sendFrame(USER_CHANGE_PWD_REQ, request.toJSONString());
        System.out.println("发送修改密码请求: " + request.toJSONString());

        JSONObject response = receiveResponse();
        System.out.println("收到响应: " + response.toJSONString());
    }

    /**
     * 测试退出登录
     */
    public void testLogout() throws IOException {
        JSONObject request = new JSONObject();

        sendFrame(USER_LOGOUT_REQ, request.toJSONString());
        System.out.println("发送退出登录请求");

        JSONObject response = receiveResponse();
        System.out.println("收到响应: " + response.toJSONString());
    }

    /**
     * 发送帧
     */
    private void sendFrame(byte frameType, String jsonData) throws IOException {
        byte[] data = jsonData.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_LENGTH + data.length);

        buffer.put(MAGIC);
        buffer.put(frameType);
        buffer.put((byte) 0); // flags
        buffer.putInt(data.length);
        buffer.put(data);
        com.alibaba.server.nio.util.NioBufferCompat.flip(buffer);

        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    /**
     * 接收响应
     */
    private JSONObject receiveResponse() throws IOException {
        // 读取帧头
        ByteBuffer headerBuffer = ByteBuffer.allocate(HEADER_LENGTH);
        while (headerBuffer.hasRemaining()) {
            int read = channel.read(headerBuffer);
            if (read == -1) {
                throw new IOException("连接已关闭");
            }
        }
        com.alibaba.server.nio.util.NioBufferCompat.flip(headerBuffer);

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
        com.alibaba.server.nio.util.NioBufferCompat.flip(dataBuffer);

        String jsonStr = new String(dataBuffer.array(), 0, dataLength, StandardCharsets.UTF_8);
        return JSON.parseObject(jsonStr);
    }
}
