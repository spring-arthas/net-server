package com.alibaba.server.nio.service.file.util;

import com.alibaba.fastjson.JSON;
import com.alibaba.server.nio.core.server.NioServerContext;
import com.alibaba.server.nio.model.SocketChannelContext;
import com.alibaba.server.nio.repository.file.service.dto.FileDto;
import com.alibaba.server.nio.repository.user.service.dto.UserDTO;
import com.alibaba.server.util.BasicUtil;
import com.alibaba.server.util.LocalTime;
import lombok.extern.slf4j.Slf4j;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.SocketChannel;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 文件写事件解析类
 * @author spring
 * */
@Slf4j
public class FileWriteEventParseUtil {

    /**
     * 解析消息并发送
     * @param socketChannel
     * @param obj
     */
    public static Boolean parseMessageAndSend(SocketChannel socketChannel, Object obj, SocketChannelContext socketChannelContext) {
        String jsonMessage = "";
        Map map = (Map) obj;
        byte b = getSendDataFrameType(map);
        if(map.containsKey("FILE_FRAME")) {
            if(b == 1 || b == 6) {
                // 文件上传或下载响应帧
                jsonMessage = JSON.toJSONString(map);
            } else if(b == 11) {
                // 文件删除帧
                jsonMessage = JSON.toJSONString((List) map.get("data"));
            }
        } else {
            if(b == 3 || b == 12) {
                // 用户列表刷新帧
                jsonMessage = JSON.toJSONString((List<UserDTO>) map.get("data"));
            } else if(b == 4) {
                // 操作文件流数据，直接发送文件流数据
                return sendMessage(socketChannel, (byte[]) map.get("streamData"), socketChannelContext);
            } else if(b == 6 || b == 7 || b == 8) {
                //个人网盘树刷新帧、创建，修改
                jsonMessage = JSON.toJSONString((FileDto) map.get("data"));
            } else {
                jsonMessage = JSON.toJSONString(map);
            }
        }

        // 发送数据
        return sendMessage(socketChannel, convertDataToBytes(jsonMessage, b), socketChannelContext);
    }

    /**
     * 获取当前待发送数据属于什么请求帧类型，即如刷新请求帧，则发送出去的数据为刷新响应帧
     * @param map 待发送的数据
     * @return
     */
    private static byte getSendDataFrameType(Map<String, Object> map) {
        return Byte.parseByte(map.get("frameType").toString(), 2);
    }

    /**
     * 数据转为bytes
     * @param jsonMessage
     * @param b 帧类型
     * @return
     */
    private static byte[] convertDataToBytes(String jsonMessage, byte b) {
        byte[] sendBytes = null;
        try {
            // 1、发送数据字节数组
            byte[] sendDataBytes = jsonMessage.getBytes("utf-8");
            // 2、发送数据字节数组长度
            byte[] sendDataBytesLength = BasicUtil.intToBytes(sendDataBytes.length);
            // 3、当前总字节数组长度 (总长度: 4B + 帧类型: 1B + 结束帧: 1B+ 实际数据长度: 4B + 实际数据内容: ...)
            byte[] sumBytesLength = BasicUtil.intToBytes(4 + 1 + 1 + sendDataBytes.length + sendDataBytesLength.length);

            // 4、发送字节数组实例化
            sendBytes = new byte[4 + 1 + 1 + sendDataBytes.length + sendDataBytesLength.length];
            System.arraycopy(sumBytesLength, 0, sendBytes, 0, sumBytesLength.length); // 数据总长度4B
            sendBytes[4] = b; // 帧类型1B
            sendBytes[5] = (byte) 1; // 结束帧1B
            System.arraycopy(sendDataBytesLength, 0, sendBytes, 6, sendDataBytesLength.length); // 数据长度4B
            System.arraycopy(sendDataBytes, 0, sendBytes, (sendDataBytesLength.length + 6), sendDataBytes.length); // 数据字节
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }

        return sendBytes;
    }

    /**
     * 发送数据
     * @param socketChannel
     * @param bytes
     */
    private static Boolean sendMessage(SocketChannel socketChannel, byte[] bytes, SocketChannelContext socketChannelContext) {
        if(null == bytes) {
            return Boolean.FALSE;
        }
        try {
            // 发送数据
            ByteBuffer byteBuffer = ByteBuffer.allocateDirect(bytes.length);
            byteBuffer.put(bytes);
            byteBuffer.flip();
            if(!socketChannel.isConnected()) {
                log.warn("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] CoreServerContext | --> socketChannel is not connected, can not send data, remoteAddress = {}, localAddress = {}", socketChannelContext.getRemoteAddress(), socketChannelContext.getLocalAddress());
                NioServerContext.closedAndRelease(socketChannel);
                return Boolean.FALSE;
            }

            while (byteBuffer.hasRemaining()) {
                int sendBytes = socketChannel.write(byteBuffer);
                /*log.info("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] WriteEventHandler | --> send bytes success, count = {}, remoteAddress = {}, thread = {}",
                    sendBytes, socketChannelContext.getRemoteAddress(), Thread.currentThread().getName());*/
            }

            return Boolean.TRUE;
            // 取消写事件注册,防止重复发送消息,重新注册读事件，否则无法继续进行事件读取
            //selectionKey.interestOps(selectionKey.interestOps() & ~SelectionKey.OP_WRITE);
            //selectionKey.interestOps(selectionKey.interestOps() | SelectionKey.OP_READ);
        } catch (ClosedByInterruptException e) {
            // 如果当前执行sendMessage方法所在的线程被中断，则对应的SocketChannel将直接被关闭，此时无法再当前SocketChannel通道上执行I/O操作
            log.info("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] WriteEventHandler | --> 当前线程 [{}] 被中断, 数据发送失败, 通道关闭状态 = [{}], 关闭地址 = [{}]",
                Thread.currentThread().getName(), socketChannel.socket().isClosed(), socketChannelContext.getRemoteAddress());
            return Boolean.FALSE;
        } catch (IOException e) {
            log.error("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] WriteEventHandler | --> 消息发送失败， thread = {}，error = {}", Thread.currentThread().getName(), e.getMessage());
            return Boolean.FALSE;
        }
    }
}
