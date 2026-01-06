package com.alibaba.server.nio.handler.worker;

import com.alibaba.fastjson.JSON;
import com.alibaba.server.nio.core.server.BasicServer;
import com.alibaba.server.nio.core.server.NioServerContext;
import com.alibaba.server.nio.handler.pipe.standard.DefaultChannelPipeLine;
import com.alibaba.server.nio.model.SocketChannelContext;
import com.alibaba.server.nio.model.TransportDataModel;
import com.alibaba.server.nio.model.constant.ChannelEventModelEnum;
import com.alibaba.server.util.LocalTime;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.time.LocalDateTime;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @Auther: YSFY
 * @Date: 2020-11-22 09:41
 * @Pacage_name: com.alibaba.server.nio.handler.worker
 * @Project_Name: net-server
 * @Description: 工作者线程池
 * 
 *               优化说明：每个 SocketChannel 复用同一个 Runnable，避免频繁创建任务对象
 *               核心机制：
 *               1. 使用 ConcurrentHashMap 缓存每个 SocketChannel 对应的 ChannelWorker
 *               2. ChannelWorker 内部维护一个阻塞队列，循环处理提交的数据
 *               3. 首次提交时创建并提交 ChannelWorker 到线程池，后续只需向队列添加数据
 */

@Slf4j
@SuppressWarnings("all")
public class WorkerThreadPool {
    public static final AtomicInteger atomicInteger = new AtomicInteger(0);

    /**
     * 缓存每个 SocketChannel 对应的 ChannelWorker
     * Key: SocketChannel 的远程地址字符串（作为唯一标识）
     * Value: 对应的 ChannelWorker
     */
    private static final ConcurrentHashMap<String, ChannelWorker> channelWorkerMap = new ConcurrentHashMap<>();

    private static final ExecutorService executorService = new ThreadPoolExecutor(
            Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().availableProcessors(), 3000,
            TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(1000),
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    return new Thread(r, "WORKER_THREAD_POOL_" + atomicInteger.incrementAndGet());
                }
            }, new RejectedExecutionHandler() {
                @Override
                public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
                    if (!executor.isShutdown()) {
                        try {
                            log.info("[ " + LocalTime.formatDate(LocalDateTime.now())
                                    + " ] WorkerThreadPool | --> 工作线程池待处理任务数已到峰值，将阻塞直至任务提交至线程池");
                            executor.getQueue().put(r);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                }
            });

    /**
     * 提交读事件任务
     * 
     * 优化后的逻辑：
     * 1. 首次提交：创建 ChannelWorker 并提交到线程池
     * 2. 后续提交：直接向已有的 ChannelWorker 队列中添加数据
     * 
     * @param socketChannelContext 包含待处理数据的通道上下文
     */
    public static void submit(SocketChannelContext socketChannelContext) {
        String dataType = socketChannelContext.getTransportDataModel().getDataType();

        // 目前只处理文件上传任务
        if (!StringUtils.equals(ChannelEventModelEnum.FILE_UPLOAD.getName(), dataType)) {
            // 文字传输和文件下载暂未实现，直接返回
            return;
        }

        // 使用远程地址作为 SocketChannel 的唯一标识
        String channelKey = socketChannelContext.getRemoteAddress();
        if (channelKey == null) {
            log.warn("[ " + LocalTime.formatDate(LocalDateTime.now())
                    + " ] WorkerThreadPool | --> SocketChannelContext 的远程地址为空，无法提交任务");
            return;
        }

        // 深拷贝 TransportDataModel，避免原始数据被清空后影响处理
        TransportDataModel transportDataModelCopy = new TransportDataModel();
        transportDataModelCopy.setDataType(socketChannelContext.getTransportDataModel().getDataType());
        transportDataModelCopy.setWaitHandleDataList(
                new java.util.ArrayList<>(socketChannelContext.getTransportDataModel().getWaitHandleDataList()));

        // 获取或创建 ChannelWorker
        ChannelWorker worker = channelWorkerMap.computeIfAbsent(channelKey, key -> {
            ChannelWorker newWorker = new ChannelWorker(socketChannelContext, channelKey);
            // 首次创建时提交到线程池
            executorService.submit(newWorker);
            log.info("[ " + LocalTime.formatDate(LocalDateTime.now())
                    + " ] WorkerThreadPool | --> 为通道 {} 创建新的 ChannelWorker 并提交到线程池", channelKey);
            return newWorker;
        });

        // 向 ChannelWorker 的队列中添加待处理数据
        if (!worker.offerData(transportDataModelCopy)) {
            log.warn("[ " + LocalTime.formatDate(LocalDateTime.now())
                    + " ] WorkerThreadPool | --> 通道 {} 的任务队列已满，数据可能丢失", channelKey);
        }
    }

    /**
     * 移除指定通道的 ChannelWorker
     * 应在 SocketChannel 关闭时调用，用于清理资源
     * 
     * @param channelKey 通道的唯一标识（远程地址）
     */
    public static void remove(String channelKey) {
        if (channelKey == null) {
            return;
        }
        ChannelWorker worker = channelWorkerMap.remove(channelKey);
        if (worker != null) {
            worker.stop();
            log.info("[ " + LocalTime.formatDate(LocalDateTime.now())
                    + " ] WorkerThreadPool | --> 移除通道 {} 的 ChannelWorker", channelKey);
        }
    }

    /**
     * 获取当前活跃的 ChannelWorker 数量
     */
    public static int getActiveWorkerCount() {
        return channelWorkerMap.size();
    }

    /**
     * ChannelWorker：每个 SocketChannel 对应的持久化任务
     * 
     * 核心机制：
     * 1. 内部维护一个阻塞队列，存储待处理的 TransportDataModel
     * 2. run() 方法中循环从队列取数据并处理
     * 3. 通道关闭时设置 running 标志为 false，任务自动退出
     */
    private static class ChannelWorker implements Runnable {
        private final SocketChannelContext socketChannelContext;
        private final String channelKey;
        private final BlockingQueue<TransportDataModel> dataQueue;
        private final AtomicBoolean running;

        /**
         * 队列获取数据的超时时间（毫秒）
         * 超时后检查 running 状态，防止通道已关闭但任务仍在阻塞
         */
        private static final long POLL_TIMEOUT_MS = 5000;

        public ChannelWorker(SocketChannelContext socketChannelContext, String channelKey) {
            this.socketChannelContext = socketChannelContext;
            this.channelKey = channelKey;
            this.dataQueue = new LinkedBlockingQueue<>(1000);
            this.running = new AtomicBoolean(true);
        }

        /**
         * 向队列中添加待处理数据
         * 
         * @param data 待处理的数据
         * @return 是否添加成功
         */
        public boolean offerData(TransportDataModel data) {
            return dataQueue.offer(data);
        }

        /**
         * 停止任务
         */
        public void stop() {
            running.set(false);
        }

        /**
         * 检查 SocketChannel 是否已关闭
         * 
         * @return true 表示通道已关闭，需要退出任务
         */
        private boolean isChannelClosed() {
            try {
                if (this.socketChannelContext.getTransportProtocol() == null) {
                    return true;
                }
                SocketChannel socketChannel = this.socketChannelContext.getTransportProtocol().getSocketChannel();
                // 通道为空或已关闭，需要退出任务
                return socketChannel == null || !socketChannel.isOpen();
            } catch (Exception e) {
                // 获取通道状态异常，保守起见认为通道已关闭
                log.warn("[ " + LocalTime.formatDate(LocalDateTime.now())
                        + " ] ChannelWorker | --> 检查通道 {} 状态时发生异常: {}", channelKey, e.getMessage());
                return true;
            }
        }

        @Override
        public void run() {
            while (running.get()) {
                try {
                    // 从队列中获取数据，带超时
                    TransportDataModel data = dataQueue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);

                    if (data == null) {
                        // 超时未获取到数据，检查 SocketChannel 状态
                        // 只有当通道关闭时才退出任务，避免因网络延迟导致任务错误终止
                        if (isChannelClosed()) {
                            log.info("[ " + LocalTime.formatDate(LocalDateTime.now())
                                    + " ] ChannelWorker | --> 通道 {} 已关闭，退出任务", channelKey);
                            break;
                        }
                        // 通道仍然打开，继续等待数据
                        continue;
                    }

                    // 处理数据
                    this.processData(data);

                } catch (InterruptedException e) {
                    log.warn("[ " + LocalTime.formatDate(LocalDateTime.now())
                            + " ] ChannelWorker | --> 通道 {} 的任务被中断", channelKey);
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("[ " + LocalTime.formatDate(LocalDateTime.now())
                            + " ] ChannelWorker | --> 通道 {} 处理数据异常: {}", channelKey, ExceptionUtils.getStackTrace(e));
                    // 出现异常时关闭通道并释放资源
                    this.closeChannelOnError();
                    break;
                }
            }

            // 任务退出时从 Map 中移除
            channelWorkerMap.remove(channelKey);
            log.info("[ " + LocalTime.formatDate(LocalDateTime.now())
                    + " ] ChannelWorker | --> 通道 {} 的 ChannelWorker 已退出", channelKey);
        }

        /**
         * 处理单个数据
         */
        private void processData(TransportDataModel data) throws IOException {
            // 尝试获取锁
            while (!BasicServer.fileLock.tryLock()) {
                try {
                    TimeUnit.MILLISECONDS.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            try {
                // 更新 socketChannelContext 中的待处理数据
                this.socketChannelContext.getTransportDataModel()
                        .setWaitHandleDataList(data.getWaitHandleDataList());

                // 执行处理管道
                DefaultChannelPipeLine defaultChannelPipeLine = (DefaultChannelPipeLine) this.socketChannelContext
                        .getChannelPipeLine();
                defaultChannelPipeLine.executeHandler(this.socketChannelContext);
            } finally {
                // 释放锁
                BasicServer.fileLock.unlock();

                // 清理已处理的数据
                if (this.socketChannelContext.getTransportProtocol() != null
                        && this.socketChannelContext.getTransportProtocol().getRealList() != null) {
                    this.socketChannelContext.getTransportProtocol().getRealList().clear();
                }
            }

        }

        /**
         * 异常时关闭通道并释放资源
         */
        private void closeChannelOnError() {
            try {
                if (this.socketChannelContext.getTransportProtocol() == null) {
                    return;
                }
                SocketChannel socketChannel = this.socketChannelContext.getTransportProtocol().getSocketChannel();
                if (socketChannel != null && socketChannel.isOpen()) {
                    NioServerContext.closedAndRelease(socketChannel);
                    log.info("[ " + LocalTime.formatDate(LocalDateTime.now())
                            + " ] ChannelWorker | --> 异常发生，已关闭通道, remoteAddress = {}, thread = {}",
                            this.socketChannelContext.getRemoteAddress(), Thread.currentThread().getName());
                }
            } catch (Exception ex) {
                log.error("[ " + LocalTime.formatDate(LocalDateTime.now())
                        + " ] ChannelWorker | --> 关闭通道异常: {}", ex.getMessage());
            }
        }
    }
}
