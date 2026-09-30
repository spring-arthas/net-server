package com.alibaba.server.nio.model;

import com.alibaba.server.nio.handler.pipe.ChannelPipeLine;
import com.alibaba.server.nio.repository.user.service.dto.UserDTO;
import com.alibaba.server.nio.service.ratelimit.RateLimiter;
import lombok.Data;

import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

/**
 * @author: duyao
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
     * 当前通道绑定的用户信息
     * */
    private UserDTO userDTO;
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
     * 当前连接持有限速器（用于上传速率控制）
     */
    private volatile RateLimiter rateLimiter;

    /**
     * 是否处于读暂停状态
     * true: OP_READ 已取消，正在等待限速恢复
     */
    private volatile boolean isReadPaused = false;

    /**
     * [修复] 通道对应的 SelectionKey 引用。
     * 注册时由 Acceptor 写入，用于 WorkerThreadPool 队列满时
     * 暂停/恢复该连接的 OP_READ（背压），避免 selector 线程被阻塞。
     */
    private volatile java.nio.channels.SelectionKey selectionKey;

    /**
     * 待执行的限速恢复任务（用于连接断开时取消）
     */
    private volatile java.util.concurrent.ScheduledFuture<?> pendingResumeTask;

    /**
     * 读暂停操作的同步锁
     */
    private final Object readPauseLock = new Object();

    /**
     * 获取读暂停锁
     */
    public Object getReadPauseLock() {
        return readPauseLock;
    }

    /**
     * [修复] 队列背压：暂停本通道 OP_READ。
     * 当 ChannelWorker 队列已满、无法继续接收数据时调用。
     * 暂停后 TCP 接收窗口会填满，客户端自然减速，队列得以消化。
     * 不阻塞调用线程（selector），仅取消 OP_READ 注册。
     */
    public void pauseReadForBackpressure() {
        synchronized (readPauseLock) {
            if (isReadPaused) {
                return;
            }
            java.nio.channels.SelectionKey key = this.selectionKey;
            if (key == null || !key.isValid()) {
                return;
            }
            try {
                key.interestOps(key.interestOps() & ~java.nio.channels.SelectionKey.OP_READ);
                this.isReadPaused = true;
                logBackpressure("暂停读取(队列背压)");
            } catch (java.nio.channels.CancelledKeyException e) {
                // 键已取消，无需处理
            }
        }
    }

    /**
     * [修复] 队列背压：恢复本通道 OP_READ。
     * 由 ChannelWorker 在队列消化到低水位后调用。
     */
    public void resumeReadForBackpressure() {
        synchronized (readPauseLock) {
            if (!isReadPaused) {
                return;
            }
            java.nio.channels.SelectionKey key = this.selectionKey;
            if (key == null || !key.isValid()) {
                this.isReadPaused = false;
                return;
            }
            try {
                key.interestOps(key.interestOps() | java.nio.channels.SelectionKey.OP_READ);
                key.selector().wakeup();
                this.isReadPaused = false;
                logBackpressure("恢复读取(队列已消化)");
            } catch (java.nio.channels.CancelledKeyException e) {
                this.isReadPaused = false;
            }
        }
    }

    private void logBackpressure(String action) {
        // 用最轻量的方式记录，避免每个连接高频打印
        if (System.currentTimeMillis() % 100 < 5) {
            System.out.println("[SocketChannelContext] " + action + ", remote=" + remoteAddress);
        }
    }

    /**
     * 设置待执行的恢复任务
     */
    public void setPendingResumeTask(java.util.concurrent.ScheduledFuture<?> task) {
        this.pendingResumeTask = task;
    }

    /**
     * 获取待执行的恢复任务
     */
    public java.util.concurrent.ScheduledFuture<?> getPendingResumeTask() {
        return pendingResumeTask;
    }

    /**
     * 取消待执行的恢复任务
     */
    public void cancelPendingResumeTask() {
        java.util.concurrent.ScheduledFuture<?> task = this.pendingResumeTask;
        if (task != null && !task.isDone()) {
            task.cancel(false);
            this.pendingResumeTask = null;
        }
    }

    /**
     * 清理限速相关资源
     */
    public void cleanupRateLimiter() {
        cancelPendingResumeTask();
        this.rateLimiter = null;
        this.isReadPaused = false;
    }

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
