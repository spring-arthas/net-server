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
     * 通道地址
     * */
    private String localAddress, remoteAddress;

    /**
     * 通道处理管道
     * */
    private ChannelPipeLine channelPipeLine;

    /**
     * 通道数据发送队列
     * */
    private BlockingQueue<Object> blockingQueue;

    /**
     * 通道协议
     * */
    private TransportProtocol transportProtocol;

    /**
     * 通道缓冲区
     * */
    private ByteBuffer byteBuffer;

    /**
     * 通道附件标识(标识当前通道附件SocketChannelContext属于文件通道还是聊天服务通道)
     * */
    private String channelFlag;

    /**
     * 当前通道即将要处理的数据
     * */
    private Object data;
}
