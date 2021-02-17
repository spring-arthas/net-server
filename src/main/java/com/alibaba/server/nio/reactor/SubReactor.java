package com.alibaba.server.nio.reactor;

import com.alibaba.server.common.BasicConstant;
import com.alibaba.server.nio.core.server.NioServerContext;
import com.alibaba.server.nio.handler.pipe.standard.DefaultChannelPipeLine;
import com.alibaba.server.nio.handler.pipe.standard.SimpleChannelContext;
import com.alibaba.server.nio.handler.worker.WorkerThreadPool;
import com.alibaba.server.nio.model.SocketChannelContext;
import com.alibaba.server.nio.model.TransportProtocol;
import com.alibaba.server.nio.model.constant.EventModelEnum;
import com.alibaba.server.nio.service.chat.handler.ChatDecodeHandler;
import com.alibaba.server.nio.service.chat.handler.ChatRealDataHandler;
import com.alibaba.server.nio.service.file.handler.*;
import com.alibaba.server.util.LocalTime;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.time.LocalDateTime;
import java.util.concurrent.*;

/**
 * @Auther: YSFY
 * @Date: 2020-11-21 15:47
 * @Pacage_name: com.alibaba.server.nio.reactor
 * @Project_Name: net-server
 * @Description: Reactor子线程
 */

@Slf4j
@SuppressWarnings("all")
public class SubReactor implements Runnable {
    private String remoteAddress = "", subReactor = "";
    private SocketChannel socketChannel;
    // 持有的当前通道对应的SocketChannelContext上下文
    private SocketChannelContext socketChannelContext = null;
    private Selector selector;
    // subReactor线程消息处理队列
    private final BlockingQueue<Object> queue = new LinkedBlockingQueue<>(1000);

    public SubReactor(Selector selector, SocketChannel channel, String subReactor) throws IOException {
        this.socketChannel = channel;
        this.selector = selector;
        this.subReactor = subReactor;
        this.register();
    }

    /**
     * 注册Socket以及配置当前通道上下文附件
     * @throws IOException
     * */
    private void register() {
        try {
            int eventOpt = 0;
            String registerType = "";

            // 1、基于聊天的subReactor线程
            if(StringUtils.equals(EventModelEnum.CHAT_TASK.getName(), subReactor)) {
                // 1.1、事件订阅索引
                eventOpt = SelectionKey.OP_READ | SelectionKey.OP_WRITE;

                // 1.2、设置通道链处理器 --> 聊天消息解码器 --> 聊天消息真实数据处理器
                socketChannelContext = createModel(socketChannel);
                socketChannelContext.setChannelFlag(BasicConstant.CHAT_CHANNEL_CONTEXT);
                socketChannelContext.getChannelPipeLine().addHandler(new SimpleChannelContext(), new ChatDecodeHandler());
                socketChannelContext.getChannelPipeLine().addHandler(new SimpleChannelContext(), new ChatRealDataHandler());

                registerType = BasicConstant.REGISTER_TYPE_CHAT;
            }

            // 2、订阅事件值为0或通道附件为空
            if(eventOpt == 0 || null == socketChannelContext) {
                throw new RuntimeException("subReactor Thread register failed, can not assign event opt or create attachment for socketChannel.....");
            }

            // 3、通道注册添加附件
            NioServerContext.EventRegister(socketChannel, this.selector, eventOpt).attach(socketChannelContext);
            log.info("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] SubReactor | --> 聊天服务通道 [{}] 注册成功", socketChannelContext.getRemoteAddress());
        } catch (Exception e) {
            log.error("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] SubReactor | --> 聊天服务通道 [{}] 注册失败, error = {}", socketChannelContext.getRemoteAddress(), e.getMessage());

            // 4、注册失败直接关闭当前通道
            NioServerContext.closedAndRelease(socketChannel);
        }
    }

    /**
     * 创建SocketChannel通道附件
     * @param socketChannel
     * @return
     * @throws IOException
     */
    private SocketChannelContext createModel(SocketChannel socketChannel) throws IOException {
        // 1、配置SocketChannel基本参数
        this.socketChannel.configureBlocking(false);
        // 是否启用TCP心跳机制 true：启用
        this.socketChannel.socket().setKeepAlive(true);
        // 是否一有数据就马上发送。 true：启用
        this.socketChannel.socket().setTcpNoDelay(false);
        // 优雅的关闭socket连接，并发送-1 (优雅地关闭套接字，或者立刻关闭)
        this.socketChannel.socket().setSoLinger(true, -1);
        // 对ServerSocket来说表示等待连接的最长空等待时间; 对Socket来说表示读数据最长空等待时间。
        this.socketChannel.socket().setSoTimeout(Integer.parseInt(NioServerContext.getValue(BasicConstant.SOCKET_TIMEOUT)));
        this.socketChannel.socket().setReceiveBufferSize(Integer.parseInt(NioServerContext.getValue(BasicConstant.SOCKET_RECEIVE_BUFFER_SIZE)));
        this.socketChannel.socket().setSendBufferSize(Integer.parseInt(NioServerContext.getValue(BasicConstant.SOCKET_SEND_BUFFER_SIZE)));

        // 2、注册SocketChannel，并添加通道附件参数
        socketChannelContext = new SocketChannelContext();
        socketChannelContext.setLocalAddress(NioServerContext.getLocalAddress(socketChannel));
        socketChannelContext.setRemoteAddress(NioServerContext.getRemoteAddress(socketChannel));
        socketChannelContext.setChannelPipeLine(new DefaultChannelPipeLine());
        socketChannelContext.setBlockingQueue(new LinkedBlockingQueue<>(1000));
        TransportProtocol transportProtocol = new TransportProtocol();
        transportProtocol.setSocketChannel(socketChannel);
        transportProtocol.setRealList(new CopyOnWriteArrayList<>());
        socketChannelContext.setTransportProtocol(transportProtocol);
        socketChannelContext.setByteBuffer(ByteBuffer.allocateDirect(Integer.parseInt(NioServerContext.getValue(BasicConstant.BYTEBUFFER))));

        return socketChannelContext;
    }

    @Override
    public void run() {
        while (true) {
            try {
                if(this.queue.isEmpty()) {
                    TimeUnit.MILLISECONDS.sleep(1);
                    continue;
                }

                // 异步提交线程池处理
                Object obj = this.queue.poll();
                if(null == obj) {
                    continue;
                }
                this.execute(obj);
            } catch (InterruptedException e) {
                // 再次触发中断，此时终端标志为不会擦除
                Thread.currentThread().interrupt();
            } finally {
                if(Thread.currentThread().isInterrupted()) {
                    break;
                }
            }
        }

        log.info("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] SubReactor | --> subReactor thread shutdown success, address = {}, thread = {}", socketChannelContext.getRemoteAddress(), Thread.currentThread().getName());
    }

    /**
     * 提交线程池进行任务处理
     * @param obj (处理数据)
     * */
    private void execute(Object obj) {
        WorkerThreadPool.submit(obj, subReactor);
    }

    /**
     * 返回当前subReactor线程持有的通道上下文
     * @return SocketChannelContext
     */
    public SocketChannelContext getSocketChannelContext() {
        return socketChannelContext;
    }

    /**
     * 返回subReactor中聊天服务待处理数据队列
     * @return queue
     */
    public BlockingQueue<Object> getQueue() {
        return this.queue;
    }
}
