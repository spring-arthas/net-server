package com.alibaba.server.nio.service.file.task;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import com.alibaba.server.common.BasicConstant;
import com.alibaba.server.nio.core.server.NioServerContext;
import com.alibaba.server.nio.model.SocketChannelContext;
import com.alibaba.server.nio.model.file.FileMessageFrame;
import com.alibaba.server.nio.service.file.model.DownloadResult;
import com.alibaba.server.nio.service.file.model.FileOperateEnum;
import com.alibaba.server.nio.service.file.util.FileWriteEventParseUtil;
import com.alibaba.server.util.BasicUtil;
import com.alibaba.server.util.LocalTime;

import lombok.extern.slf4j.Slf4j;

/**
 * @author spring
 */
@Slf4j
public class FileDownloadStreamCallable implements Callable<DownloadResult> {
    private Map<String, Object> currentDownloadFileMap;
    private SocketChannelContext socketChannelContext;

    public FileDownloadStreamCallable(Map<String, Object> currentDownloadFileMap, SocketChannelContext  socketChannelContext) {
        this.currentDownloadFileMap = currentDownloadFileMap;
        this.socketChannelContext = socketChannelContext;
    }

    @Override
    public DownloadResult call() {
        File currentDownloadFile = (File) this.currentDownloadFileMap.get("FILE");
        Long fileSize = Long.parseLong(this.currentDownloadFileMap.get("FILE_LENGTH").toString());
        FileChannel fileChannel = (FileChannel) this.currentDownloadFileMap.get("FILE_CHANNEL");
        SocketChannel socketChannel = socketChannelContext.getTransportProtocol().getSocketChannel();

        try {
            if(!fileChannel.isOpen()) {
                // 文件通道未打开，写入失败，释放资源
                NioServerContext.closedAndRelease(socketChannel);
                fileChannel.close();
                return DownloadResult.generate(BasicConstant.FILE_CHANNEL_OPEN_ERROR, Boolean.FALSE);
            }

            return this.loopSendFileDownloadStream(currentDownloadFile, fileChannel, socketChannel);
        } catch (IOException e) {
            e.printStackTrace();
            return DownloadResult.generate(e.getMessage(), Boolean.FALSE);
        }
    }

    /**
     * 循环发送文件流数据
     * @param currentDownloadFile 待下载文件
     * @param fileChannel 文件通道
     * @param socketChannel 文件流发送通道
     * @return
     *
     *         // 4B -> 当前帧的总长度；
     *         // 1B -> 文件是否读取完[0:未读取完, 1:读取完],
     *         // 4B -> 包序号
     *         // 4B -> 文件字节流长度
     */
    private DownloadResult loopSendFileDownloadStream(File currentDownloadFile, FileChannel fileChannel, SocketChannel socketChannel) throws IOException {
        int originStreamDataIndex = 4 + 1 + 4 + 4;
        // 包序号
        Integer packetIndex = 1;
        ByteBuffer byteBuffer = ByteBuffer.allocate(Integer.parseInt(NioServerContext.getValue(BasicConstant.DOWNLOAD_SEND_BYTE_BUFFER)));
        int readFileSize = 0, sumReadFileSize = 0;

        // 处于runnable的线程被中断，只会修改线程的中断标志位，并不会真正中断线程
        while (!Thread.currentThread().isInterrupted()) {
            // 从文件通道读取文件流数据(抛出IOException)
            sumReadFileSize += readFileSize = fileChannel.read(byteBuffer);
            if(readFileSize == -1) {
                // 文件流读取到末尾，表示没有文件流可读，读取已完成,主动关闭文件通道,客户端接收完文件后会主动关闭socketchannel,此时服务端也会关闭相应资源
                fileChannel.close();
                return DownloadResult.generate("文件 [" + currentDownloadFile + "] 下载流读取完成", Boolean.TRUE);
            }

            // 待发送的字节数组
            byte[] sendBytes = new byte[originStreamDataIndex + readFileSize];
            // 封装文件发送流
            packetIndex = this.encapsulationStreamData(sumReadFileSize, currentDownloadFile.length(), packetIndex, byteBuffer, sendBytes);
            // 再次包装文件流
            Map<String, Object> map = new HashMap<>(8);
            map.put("code", 200);
            map.put("streamData", sendBytes);
            map.put("frameType", FileMessageFrame.FrameType.DATA_TRANSPORT.getBit());
            map.put("time", BasicConstant.SDF.format(new Date()));

            // 如果当前线程在执行过程中被外部线程执行了中断，则parseMessageAndSend方法将会抛出ClosedByInterruptedException异常，该异常在sendMessage中被捕获，且当前
            // 通道SocketChannel也由于线程的中断被关闭，此处无需再关闭socketChannel，直接关闭文件读取通道即可
            Boolean result = FileWriteEventParseUtil.parseMessageAndSend(socketChannel, map, this.socketChannelContext);
            if(!result) {
                fileChannel.close();
                log.info("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] FileDownloadStreamCallable | --> 关闭文件下载通道(FileChannel)成功, file = {}, thread = {}", currentDownloadFile.getName(), Thread.currentThread().getName());

                // 如果不是关闭状态，则数据发送失败尤其它原因导致，则关闭socketChannel
                if(!socketChannel.socket().isClosed()) {
                    NioServerContext.closedAndRelease(socketChannel);
                    return DownloadResult.generate("文件 [" + currentDownloadFile + "] 下载线程 [" + Thread.currentThread().getName() + "] 发送数据第 [" + packetIndex + "] 个包数据失败", Boolean.FALSE);
                } else {
                    // 如果socketChannel状态为关闭，则为socketChannel所在线程被中断引起，此处不做任何操作，直接关闭文件读取通道
                    return DownloadResult.generate("文件 [" + currentDownloadFile + "] 下载线程 [" + Thread.currentThread().getName() + "] 发送数据第 [" + packetIndex + "] 个包时由于下载线程被中断,已停止数据发送", Boolean.FALSE);
                }
            }

            /*log.info("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] FileDownloadTransportStreamHandler | --> send file = [{}], packetIndex = [{}] download stream data success, thread = {}",
                currentDownloadFile.getName(), packetIndex, Thread.currentThread().getName());*/
        }

        fileChannel.close();
        NioServerContext.closedAndRelease(socketChannel);
        return DownloadResult.generate("未知原因导致线程 [ " + Thread.currentThread().getName() + " ] 被中断", Boolean.FALSE);
    }

    /**
     * 封装文件发送流
     * @param sumReadFileSize 当前从文件读取到的字节总数
     * @param fileSize   当前文件大小
     * @param packetIndex 当前文件字节数据包序号
     * @param byteBuffer 文件文件字节数据
     * @param sendBytes  待发送的字节数组
     */
    private Integer encapsulationStreamData(int sumReadFileSize, long fileSize, Integer packetIndex, ByteBuffer byteBuffer, byte[] sendBytes/*, byte[] sendMessageBytes, byte[] sendMessageLengthBytes*/) {

        // 1、当前帧总长度 4B
        byte[] frameSumLength = BasicUtil.intToBytes(sendBytes.length);
        System.arraycopy(frameSumLength, 0, sendBytes, 0, frameSumLength.length);

        // 2、是否是结束帧 1B
        sendBytes[4] = (sumReadFileSize == fileSize)?(byte)1:(byte)0;

        // 3、包序号 4B
        byte[] packetIndexBytes = BasicUtil.intToBytes(packetIndex); packetIndex++;
        System.arraycopy(packetIndexBytes, 0, sendBytes, 5, packetIndexBytes.length);

        // 4、文件流数据
        byteBuffer.flip();
        if(byteBuffer.hasRemaining()) {
            byte[] fileStreamBytes = new byte[byteBuffer.remaining()];
            byteBuffer.get(fileStreamBytes);

            // 5、文件字节流长度 4B
            byte[] fileStreamByteLength = BasicUtil.intToBytes(fileStreamBytes.length);
            System.arraycopy(fileStreamByteLength, 0, sendBytes, 9, fileStreamByteLength.length);

            // 6、拷贝文件流数据
            System.arraycopy(fileStreamBytes, 0, sendBytes, 13, fileStreamBytes.length);

            byteBuffer.compact();
        }

        return packetIndex;
    }
}
