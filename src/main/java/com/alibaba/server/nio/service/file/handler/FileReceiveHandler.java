package com.alibaba.server.nio.service.file.handler;

import com.alibaba.server.nio.core.server.NioServerContext;
import com.alibaba.server.nio.handler.pipe.ChannelContext;
import com.alibaba.server.nio.handler.pipe.standard.SimpleChannelContext;
import com.alibaba.server.nio.model.SocketChannelContext;
import com.alibaba.server.nio.model.file.FileMessageFrame;
import com.alibaba.server.nio.service.api.AbstractChannelHandler;
import com.alibaba.server.util.LocalTime;
import lombok.extern.slf4j.Slf4j;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 文件读事件流数据处理器 --> 包括离线处理以及文件存储服务
 *
 * @author spring
 * */

@Slf4j
public class FileReceiveHandler extends AbstractChannelHandler {

    @Override
    public void handler(Object o, ChannelContext channelContext) throws IOException {
        /*SocketChannelContext socketChannelContext = (SocketChannelContext) o;
        List<Object> realList = socketChannelContext.getTransportProtocol().getRealList();

        // 1、处理文件数据(realList 中可能包含多个待处理文件)
        for(Object obj : realList) {
            FileMessageFrame fileMessageFrame = (FileMessageFrame) obj;

            // 2、服务端开始接收客户端发送的文件数据，并执行存储
            this.receiveFileData(fileMessageFrame, socketChannelContext, channelContext);
        }*/
    }

    /**
     * 开始接收客户端上传的文件
     * @param fileMessageFrame  文件帧
     * @param socketChannelContext
     */
    private void receiveFileData(FileMessageFrame fileMessageFrame, SocketChannelContext socketChannelContext, ChannelContext channelContext) {
        SocketChannel socketChannel = socketChannelContext.getTransportProtocol().getSocketChannel();
        if(!super.checkConnected(socketChannel)) {
            return;
        }

        // 1、获取文件通道
        FileChannel fileChannel = fileMessageFrame.getFileChannel();

        // 2、开始接收客户端文件
        ByteBuffer byteBuffer = socketChannelContext.getByteBuffer();
        int readBytes = 0, writeBytes = 0, sumBytes = 0;
        try {
            while (true) {
                readBytes = socketChannel.read(byteBuffer);
                // 文件末尾
                if(readBytes == -1) {
                    break;
                }

                // 读取不到数据或是客户端还未发送，需要仔细调试防止死循环释放不了锁
                if(readBytes == 0) {
                    TimeUnit.MILLISECONDS.sleep(20);
                }

                if (readBytes > 0) {
                    sumBytes += readBytes;
                    byteBuffer.flip();

                    if(byteBuffer.hasRemaining()) {
                        writeBytes += fileChannel.write(byteBuffer);
                        log.info("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] FileReceiveHandler | --> storing file, write bytes = {}, file = {}, thread = {}",
                            writeBytes, fileMessageFrame.getFileTransport().getFileName(), Thread.currentThread().getName());
                    }
                }
            }

            log.info("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] FileReceiveHandler | --> upload success, file = {}, sumBytes = {}, thread = {}",
                fileMessageFrame.getFileTransport().getFileName(), writeBytes, Thread.currentThread().getName());

            // 3、是文件传送，则设置文件消息, 用于发送给远程用户客户端以及DB文件传送任务持久化
            Map<String, Object> remoteMessageMap = new HashMap<>();
            remoteMessageMap.put("currentUserName", fileMessageFrame.getFileTransport().getLaunchUserName());
            remoteMessageMap.put("remoteUserName", fileMessageFrame.getFileTransport().getReceiveUserName());
            remoteMessageMap.put("fileName", fileMessageFrame.getFileTransport().getFileName());
            remoteMessageMap.put("filePath", fileMessageFrame.getFileTransport().getFilePath());
            remoteMessageMap.put("time", new Date());
            ((SimpleChannelContext) channelContext).setObj(remoteMessageMap);
        } catch (IOException e) {
            log.error("[" + LocalTime.formatDate(LocalDateTime.now()) + "] FileReceiveHandler | --> receive or write file error, file = {}, thread = {}, error = {}",
                fileMessageFrame.getFileTransport().getFileName(), Thread.currentThread().getName(), e.getMessage());
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            // 客户端文件上传完成，关闭文件通道以及与客户端文件传输通道
            if(!Optional.ofNullable(fileChannel).isPresent()) {
                try {
                    fileChannel.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            if(!Optional.ofNullable(socketChannel).isPresent()) {
                NioServerContext.closedAndRelease(socketChannel);
            }
        }
    }
}
