package com.alibaba.server.nio.model;

import com.alibaba.server.nio.handler.pipe.ChannelPipeLine;
import lombok.Data;

import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.util.concurrent.BlockingQueue;

/**
 * @author: YSFY
 * @Date: 2020-11-21 22:58
 * @Pacage_name: com.alibaba.server.nio.model
 * @Project_Name: net-server
 * @Description: SocketChannel上下文
 */

@Data
public class SocketChannelContext {
    /**
     * 本通道地址（包含服务端分配的本地地址和对应到远端的客户端地址）
     * */
    private String localAddress, remoteAddress;
    /**
     * 本通道处理管道, 即本通道的数据处理按照该ChannelPipeLine定义的处理器集合进行
     * */
    private ChannelPipeLine channelPipeLine;
    /**
     * 本通道数据发送队列（在通道产生了写事件时该队列存放待写数据）
     * */
    private BlockingQueue<Object> blockingQueue;
    /**
     * 本通道缓冲区，用于从通道中的SocketChannel中读取字节数据
     * */
    private ByteBuffer byteBuffer;
    /**
     * 本通道即将要处理的数据
     * */
    private TransportDataModel transportDataModel;


    /**
     * 通道协议
     * */
    private TransportProtocol transportProtocol;
    /**
     * 通道附件标识(标识当前通道附件SocketChannelContext属于文件通道还是聊天服务通道)
     * */
    private String channelFlag;

}
