package com.alibaba.server.nio.test;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.server.nio.model.file.FileMessageFrame;
import com.alibaba.server.nio.service.file.model.FileTransport;
import com.alibaba.server.util.ByteOrderConvert;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public class FileClient {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 10087;
    private static final String FILE_PATH = System.getProperty("user.dir") + "/test_upload.txt"; 

    public static void main(String[] args) throws Exception {
        // 1. 准备测试文件
        File file = new File(FILE_PATH);
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.setLength(1024 * 1024 * 10); // 10MB
            // 写入一些数据以确保不全为空
            raf.seek(0);
            raf.write("Start of file".getBytes());
            raf.seek(1024 * 1024 * 10 - 20);
            raf.write("End of file".getBytes());
        }

        System.out.println("Test file created: " + file.getAbsolutePath());

        // 2. 第一次上传（模拟中断）
        System.out.println("\n--- Starting first upload (will interrupt) ---");
        uploadFile(file, true);

        // 3. 等待一会儿，确保服务端处理完毕
        TimeUnit.SECONDS.sleep(2);

        // 4. 第二次上传（断点续传）
        System.out.println("\n--- Starting second upload (resume) ---");
        uploadFile(file, false);
        
        // 清理
        // file.delete();
    }

    private static void uploadFile(File file, boolean interrupt) throws IOException, InterruptedException {
        try (SocketChannel socketChannel = SocketChannel.open()) {
            socketChannel.connect(new InetSocketAddress(HOST, PORT));
            socketChannel.configureBlocking(true);

            // 构建 UPLOAD 帧
            FileTransport fileTransport = new FileTransport();
            fileTransport.setFileName(file.getName());
            fileTransport.setFileSize(file.length());
            fileTransport.setGroup("test_group/"); 
            fileTransport.setLaunchUserName("testUser");
            fileTransport.setTag("test_file_tag"); // 固定 Tag
            fileTransport.setOperate("UPLOAD");
            // fileTransport.setFileOperateType(FileMessageFrame.FileOperateType.STORE.getBit());

            String json = JSON.toJSONString(fileTransport);
            byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);

            // 构造 UPLOAD 帧
            ByteBuffer buffer = ByteBuffer.allocate(4 + 18 + jsonBytes.length);
            buffer.putInt(4 + 18 + jsonBytes.length); // FrameSumLength (Include self)
            buffer.put((byte) 0); // End frame
            buffer.putInt(1); // Frame index
            // FrameType (UPLOAD=0), FileType (TXT=3), FileOperateType (STORE=1)
            buffer.put((byte) 0); 
            buffer.put((byte) 3); 
            buffer.put((byte) 1); 

            // DataLength (JSON length) - 2 bytes (Big Endian)
            buffer.putShort((short) jsonBytes.length);
            
            // FileSize - 8 bytes (Big Endian)
            buffer.putLong(file.length());

            buffer.put(jsonBytes); // Append JSON
            buffer.flip();
            socketChannel.write(buffer);

            // Read response
            long offset = 0;
            ByteBuffer respHeader = ByteBuffer.allocate(10); // Total(4) + Type(1) + End(1) + Len(4)
            int read = socketChannel.read(respHeader);
            if (read > 0) {
                respHeader.flip();
                int totalLen = respHeader.getInt();
                byte type = respHeader.get();
                byte end = respHeader.get();
                int dataLen = respHeader.getInt();
                
                ByteBuffer respBody = ByteBuffer.allocate(dataLen);
                socketChannel.read(respBody);
                respBody.flip();
                String respJson = new String(respBody.array(), StandardCharsets.UTF_8);
                System.out.println("Response: " + respJson);

                JSONObject respObj = JSON.parseObject(respJson);
                String operate = respObj.getString("operate");
                if ("UPLOAD.TRANSPORT.CONTINUE".equals(operate)) {
                    offset = respObj.getLongValue("uploadedSize");
                    System.out.println("Resuming from offset: " + offset);
                } else if ("UPLOAD.TRANSPORT.CONFIRM.REPEAT".equals(operate)) {
                    System.out.println("File already exists completely.");
                    return;
                }
            } else {
                System.out.println("No response from server.");
                return;
            }

            // Start sending data from offset
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                raf.seek(offset);
                byte[] fileBuffer = new byte[32 * 1024]; // 32KB chunks
                int bytesRead;
                int frameIndex = 2; // Index starts from 2 (1 is header)
                long totalSent = offset;

                String meta = "testUser,test_file_tag";
                byte[] metaBytes = meta.getBytes(StandardCharsets.UTF_8);

                while ((bytesRead = raf.read(fileBuffer)) != -1) {
                    ByteBuffer dataFrame = ByteBuffer.allocate(10 + metaBytes.length + bytesRead);
                    dataFrame.put((byte) 0); // Not end
                    dataFrame.putInt(frameIndex++);
                    dataFrame.put((byte) 4); // DATA_TRANSPORT
                    dataFrame.put((byte) 0);
                    dataFrame.put((byte) 0);
                    dataFrame.putShort((short) metaBytes.length); // Length of metadata
                    dataFrame.put(metaBytes); // Metadata
                    dataFrame.put(fileBuffer, 0, bytesRead); // Real file data
                    
                    dataFrame.flip();
                    socketChannel.write(dataFrame);
                    
                    totalSent += bytesRead;
                    // System.out.println("Sent " + bytesRead + " bytes. Total: " + totalSent);

                    if (interrupt && totalSent > file.length() / 2) {
                        System.out.println("Interrupting upload at " + totalSent + " bytes.");
                        socketChannel.close();
                        return;
                    }
                    
                    // Simulate slow network to see logs
                    TimeUnit.MILLISECONDS.sleep(5);
                }
                
                System.out.println("Upload complete.");
            }
        }
    }
}
