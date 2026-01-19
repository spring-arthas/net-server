package com.alibaba.server.nio.model;

import com.alibaba.server.nio.handler.pipe.ChannelPipeLine;
import lombok.Data;

import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

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
     * 值为 "UPLOAD" 或 "DOWNLOAD" 或 "TEXT"
     */
    private String handlerType;

    /**
     * 对应客户端连接接入时的服务端SocketChannel
     */
    private SocketChannel socketChannel;

    /**
     * 实时数据列表（用于聊天等场景）
     */
    private java.util.List<Object> realList = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * 待写入的缓冲区队列（支持多个待写数据排队）
     * 使用队列避免快速连续调用时数据丢失
     */
    private java.util.concurrent.ConcurrentLinkedQueue<java.nio.ByteBuffer> pendingWriteQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /**
     * 通道属性（用于存储登录用户信息等）
     */
    private java.util.concurrent.ConcurrentHashMap<String, Object> attributes = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 设置属性
     */
    public void putAttribute(String key, Object value) {
        if (key != null && value != null) {
            attributes.put(key, value);
        }
    }

    /**
     * 限速器（用于上传速率控制）
     */
    private com.alibaba.server.nio.service.ratelimit.RateLimiter rateLimiter;

    /**
     * 是否处于读暂停状态
     * true: OP_READ 已取消，正在等待限速恢复
     */
    private volatile boolean isReadPaused = false;

    /**
     * 获取属性
     */
    public Object getAttribute(String key) {
        return key == null ? null : attributes.get(key);
    }

    /**
     * 移除属性
     */
    public Object removeAttribute(String key) {
        return key == null ? null : attributes.remove(key);
    }
}
