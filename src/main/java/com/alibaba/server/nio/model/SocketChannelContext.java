package com.alibaba.server.nio.model;

import com.alibaba.server.nio.handler.pipe.ChannelPipeLine;
import lombok.Data;

import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
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
     */
    private String localAddress, remoteAddress;
    /**
     * 本通道处理管道, 即本通道的数据处理按照该ChannelPipeLine定义的处理器集合进行
     */
    private ChannelPipeLine channelPipeLine;
    /**
     * 本通道缓冲区，用于从通道中的SocketChannel中读取字节数据
     */
    private ByteBuffer byteBuffer;
    /**
     * 本通道即将要处理的数据
     */
    private TransportDataModel transportDataModel;
    /**
     * 通道附件标识(标识当前通道附件SocketChannelContext属于文件通道还是聊天服务通道)
     */
    private String channelFlag;

    /**
     * Handler 类型标识（用于区分上传/下载）
     * 值为 "UPLOAD" 或 "DOWNLOAD"
     */
    private String handlerType;

    /**
     * 通道
     */
    private SocketChannel socketChannel;

    /**
     * 实时数据列表（用于聊天等场景）
     */
    private java.util.List<Object> realList = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * 待写入的缓冲区（仅在一次 write 无法完成时使用）
     * 采用「先尝试直接写，写不完才保存」的策略，减少内存占用
     */
    private java.nio.ByteBuffer pendingWriteBuffer;

}
