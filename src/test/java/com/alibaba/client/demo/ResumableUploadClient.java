package com.alibaba.client.demo;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Scanner;

/**
 * 支持断点续传的文件上传客户端Demo
 * 
 * 功能：
 * 1. 计算文件MD5
 * 2. 发送RESUME_CHECK检查断点
 * 3. 支持全新上传和断点续传
 * 4. 支持暂停和继续
 * 5. 实时进度显示
 * 
 * @author duyao
 */
public class ResumableUploadClient {

    // 服务器配置
    private static final String SERVER_HOST = "localhost";
    private static final int SERVER_PORT = 8888;

    // 帧协议常量
    private static final byte[] MAGIC = {(byte) 0xFA, (byte) 0xCE};
    private static final int HEADER_LENGTH = 8;
    
    // 帧类型
    private static final byte RESUME_CHECK = 0x05;
    private static final byte RESUME_ACK = 0x06;
    private static final byte META_FRAME = 0x01;
    private static final byte DATA_FRAME = 0x02;
    private static final byte END_FRAME = 0x03;
    private static final byte ACK_FRAME = 0x04;

    // 上传参数
    private static final int CHUNK_SIZE = 32 * 1024; // 32KB per chunk
    
    private SocketChannel socketChannel;
    private volatile boolean isPaused = false;
    private volatile boolean isRunning = true;

    public static void main(String[] args) {
        ResumableUploadClient client = new ResumableUploadClient();
        
        // 示例：上传文件
        String filePath = "/Users/hljy/Downloads/test.zip"; // 修改为实际文件路径
        Long dirId = null; // 可选，目录ID
        Long userId = 1L;  // 用户ID
        
        try {
            client.uploadFile(filePath, dirId, userId);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 上传文件（支持断点续传）
     */
    public void uploadFile(String filePath, Long dirId, Long userId) throws Exception {
        File file = new File(filePath);
        if (!file.exists()) {
            System.err.println("文件不存在: " + filePath);
            return;
        }

        // 1. 计算文件MD5
        // 方式1：基于文件名快速计算（推荐，秒级）
        String md5 = calculateFileNameMD5(file);
        System.out.println("文件标识MD5: " + md5);
        
        // 方式2：基于文件内容计算（可选，耗时但更准确）
        // 取消注释下面两行可启用文件内容MD5
        // System.out.println("正在计算文件内容MD5...");
        // String md5 = calculateFileMD5(file);
        // System.out.println("文件内容MD5: " + md5);

        // 2. 连接服务器
        socketChannel = SocketChannel.open();
        socketChannel.connect(new InetSocketAddress(SERVER_HOST, SERVER_PORT));
        socketChannel.configureBlocking(true);
        System.out.println("已连接到服务器: " + SERVER_HOST + ":" + SERVER_PORT);

        // 3. 发送断点检查请求
        long uploadedSize = sendResumeCheck(file, md5, dirId, userId);
        
        // 4. 开始上传（从断点或0开始）
        if (uploadedSize > 0) {
            System.out.println("检测到断点，从 " + formatBytes(uploadedSize) + " 继续上传");
        } else {
            System.out.println("全新上传");
        }
        
        // 5. 启动暂停控制线程（可选）
        startPauseControlThread();
        
        // 6. 执行上传
        uploadFileData(file, md5, uploadedSize, dirId, userId);
        
        // 7. 关闭连接
        socketChannel.close();
        System.out.println("上传完成！");
    }

    /**
     * 发送断点检查请求
     * 
     * @return 已上传的字节数
     */
    private long sendResumeCheck(File file, String md5, Long dirId, Long userId) throws Exception {
        // 构造JSON数据
        JSONObject meta = new JSONObject();
        meta.put("md5", md5);
        meta.put("fileName", file.getName());
        meta.put("fileSize", file.length());
        meta.put("fileType", getFileExtension(file.getName()));
        if (dirId != null) {
            meta.put("dirId", dirId);
        }
        if (userId != null) {
            meta.put("userId", userId);
        }

        // 发送RESUME_CHECK帧
        sendFrame(RESUME_CHECK, meta.toJSONString().getBytes("UTF-8"));
        
        // 接收RESUME_ACK响应
        JSONObject ack = receiveFrame();
        
        if (ack == null) {
            throw new IOException("未收到服务器响应");
        }
        
        String status = ack.getString("status");
        long uploadedSize = ack.getLongValue("uploadedSize");
        String message = ack.getString("message");
        
        System.out.println("服务器响应: " + message + " (status=" + status + ")");
        
        if ("resume".equals(status)) {
            // 断点续传
            return uploadedSize;
        } else if ("new".equals(status)) {
            // 全新上传，需要发送META_FRAME
            sendMetaFrame(file, md5, dirId, userId);
            return 0;
        } else {
            throw new IOException("服务器错误: " + message);
        }
    }

    /**
     * 发送元数据帧（全新上传时）
     */
    private void sendMetaFrame(File file, String md5, Long dirId, Long userId) throws Exception {
        JSONObject meta = new JSONObject();
        meta.put("md5", md5);
        meta.put("fileName", file.getName());
        meta.put("fileSize", file.length());
        meta.put("fileType", getFileExtension(file.getName()));
        if (dirId != null) {
            meta.put("dirId", dirId);
        }
        if (userId != null) {
            meta.put("userId", userId);
        }

        sendFrame(META_FRAME, meta.toJSONString().getBytes("UTF-8"));
        
        // 接收ACK
        JSONObject ack = receiveFrame();
        if (ack == null || !"ready".equals(ack.getString("status"))) {
            throw new IOException("服务器未就绪");
        }
    }

    /**
     * 上传文件数据
     */
    private void uploadFileData(File file, String md5, long startOffset, Long dirId, Long userId) throws Exception {
        long fileSize = file.length();
        long uploaded = startOffset;
        
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            // 跳到断点位置
            raf.seek(startOffset);
            
            byte[] buffer = new byte[CHUNK_SIZE];
            int bytesRead;
            long lastPrintTime = System.currentTimeMillis();
            
            while ((bytesRead = raf.read(buffer)) != -1 && isRunning) {
                // 检查是否暂停
                while (isPaused && isRunning) {
                    Thread.sleep(100);
                }
                
                if (!isRunning) {
                    System.out.println("\n上传已取消");
                    break;
                }
                
                // 发送数据帧
                byte[] data = (bytesRead == buffer.length) ? buffer : java.util.Arrays.copyOf(buffer, bytesRead);
                sendFrame(DATA_FRAME, data);
                
                uploaded += bytesRead;
                
                // 显示进度（每100ms更新一次）
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastPrintTime > 100) {
                    printProgress(uploaded, fileSize);
                    lastPrintTime = currentTime;
                }
            }
            
            // 显示最终进度
            printProgress(uploaded, fileSize);
            System.out.println();
            
            if (isRunning && uploaded == fileSize) {
                // 发送结束帧
                JSONObject endData = new JSONObject();
                endData.put("taskId", ""); // 服务端会自动匹配
                sendFrame(END_FRAME, endData.toJSONString().getBytes("UTF-8"));
                
                // 接收确认
                JSONObject ack = receiveFrame();
                if (ack != null && "success".equals(ack.getString("status"))) {
                    System.out.println("服务器确认上传成功！");
                }
            }
        }
    }

    /**
     * 发送帧
     */
    private void sendFrame(byte type, byte[] data) throws IOException {
        int length = (data == null) ? 0 : data.length;
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_LENGTH + length);
        
        buffer.put(MAGIC);
        buffer.put(type);
        buffer.put((byte) 0); // flags
        buffer.putInt(length);
        
        if (data != null) {
            buffer.put(data);
        }
        
        buffer.flip();
        socketChannel.write(buffer);
    }

    /**
     * 接收帧
     */
    private JSONObject receiveFrame() throws IOException {
        // 读取头部
        ByteBuffer header = ByteBuffer.allocate(HEADER_LENGTH);
        int read = 0;
        while (read < HEADER_LENGTH) {
            int n = socketChannel.read(header);
            if (n == -1) {
                return null;
            }
            read += n;
        }
        header.flip();
        
        // 验证魔数
        byte[] magic = new byte[2];
        header.get(magic);
        if (magic[0] != MAGIC[0] || magic[1] != MAGIC[1]) {
            throw new IOException("Invalid magic number");
        }
        
        byte type = header.get();
        byte flags = header.get();
        int length = header.getInt();
        
        // 读取数据
        if (length > 0) {
            ByteBuffer dataBuffer = ByteBuffer.allocate(length);
            read = 0;
            while (read < length) {
                int n = socketChannel.read(dataBuffer);
                if (n == -1) {
                    return null;
                }
                read += n;
            }
            dataBuffer.flip();
            
            byte[] data = new byte[length];
            dataBuffer.get(data);
            
            String jsonStr = new String(data, "UTF-8");
            return JSON.parseObject(jsonStr);
        }
        
        return new JSONObject();
    }

    /**
     * 快速计算文件标识MD5（基于文件名+大小+修改时间）
     * 优点：速度快，秒级完成
     * 缺点：不同内容的同名文件可能冲突
     * 
     * 适用场景：
     * - 大文件上传（节省时间）
     * - 文件名唯一性有保障的场景
     * - 快速断点续传
     */
    private String calculateFileNameMD5(File file) throws Exception {
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        
        // 组合：文件路径 + 文件大小 + 最后修改时间
        String input = file.getAbsolutePath() + 
                       "|" + file.length() + 
                       "|" + file.lastModified();
        
        byte[] digest = md5.digest(input.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        
        return sb.toString();
    }

    /**
     * 计算文件内容MD5（完整校验）
     * 优点：准确，保证文件唯一性
     * 缺点：大文件耗时长（500MB约5-10秒）
     * 
     * 适用场景：
     * - 需要验证文件完整性
     * - 文件去重
     * - 安全性要求高的场景
     */
    private String calculateFileMD5(File file) throws Exception {
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        
        try (FileInputStream fis = new FileInputStream(file);
             BufferedInputStream bis = new BufferedInputStream(fis)) {
            
            byte[] buffer = new byte[8192];
            int bytesRead;
            long totalRead = 0;
            long fileSize = file.length();
            
            while ((bytesRead = bis.read(buffer)) != -1) {
                md5.update(buffer, 0, bytesRead);
                totalRead += bytesRead;
                
                // 显示MD5计算进度
                if (totalRead % (1024 * 1024) == 0) {
                    int progress = (int) ((totalRead * 100) / fileSize);
                    System.out.print(String.format("\rMD5计算进度: %d%%", progress));
                }
            }
            System.out.println("\rMD5计算完成       ");
        }
        
        byte[] digest = md5.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        
        return sb.toString();
    }

    /**
     * 打印上传进度
     */
    private void printProgress(long uploaded, long total) {
        double progress = (uploaded * 100.0) / total;
        String progressBar = generateProgressBar(progress, 30);
        
        System.out.print(String.format("\r上传中: %s %.2f%% (%s/%s)",
                progressBar, progress,
                formatBytes(uploaded),
                formatBytes(total)));
    }

    /**
     * 生成进度条
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
     * 获取文件扩展名
     */
    private String getFileExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return (dotIndex > 0) ? fileName.substring(dotIndex + 1) : "";
    }

    /**
     * 启动暂停控制线程（可选功能）
     * 输入 'p' 暂停，'r' 继续，'q' 退出
     */
    private void startPauseControlThread() {
        Thread controlThread = new Thread(() -> {
            Scanner scanner = new Scanner(System.in);
            System.out.println("提示: 输入 'p' 暂停, 'r' 继续, 'q' 退出");
            
            while (isRunning) {
                if (scanner.hasNextLine()) {
                    String cmd = scanner.nextLine().trim().toLowerCase();
                    
                    if ("p".equals(cmd)) {
                        isPaused = true;
                        System.out.println("\n上传已暂停，输入 'r' 继续...");
                    } else if ("r".equals(cmd)) {
                        isPaused = false;
                        System.out.println("\n上传已继续...");
                    } else if ("q".equals(cmd)) {
                        isRunning = false;
                        System.out.println("\n正在退出...");
                    }
                }
            }
        });
        
        controlThread.setDaemon(true);
        controlThread.start();
    }
}
