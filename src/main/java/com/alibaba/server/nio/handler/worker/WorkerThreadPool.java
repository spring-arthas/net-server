package com.alibaba.server.nio.handler.worker;

import com.alibaba.server.nio.model.SocketChannelContext;
import com.alibaba.server.nio.model.TransportDataModel;
import com.alibaba.server.nio.model.constant.ChannelEventModelEnum;
import com.alibaba.server.nio.service.chat.task.ChatReadEventTask;
import com.alibaba.server.nio.service.file.task.FileUploadTask;
import com.alibaba.server.nio.service.file.task.FileDownloadTask;
import com.alibaba.server.util.LocalTime;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @Auther: YSFY
 * @Date: 2020-11-22 09:41
 * @Pacage_name: com.alibaba.server.nio.handler.worker
 * @Project_Name: net-server
 * @Description: 工作者线程池
 */

@Slf4j
@SuppressWarnings("all")
public class WorkerThreadPool {
    public static final AtomicInteger atomicInteger = new AtomicInteger(0);
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
     * @param eventModel
     */
    public static void submit(SocketChannelContext SocketChannelContext) {
        Runnable runnable = null;
        String dataType = SocketChannelContext.getTransportDataModel().getDataType();
        // 创建基于聊天的任务
        if (StringUtils.equals(ChannelEventModelEnum.TEXT_TRANSMISSION.getName(), dataType)) {
            // runnable = new ChatReadEventTask((Map) obj);
        }

        // 创建文件上传服务
        if (StringUtils.equals(ChannelEventModelEnum.FILE_UPLOAD.getName(), dataType)) {
            runnable = new FileUploadTask(SocketChannelContext);
        }

        // 创建文件下载任务
        if (StringUtils.equals(ChannelEventModelEnum.FILE_DOWNLOAD.getName(), dataType)) {
            // runnable = new FileDownloadTask((Map) obj);
        }

        if (null != runnable) {
            executorService.submit(runnable);
            /*
             * log.info("[ " + LocalTime.formatDate(LocalDateTime.now()) +
             * " ]  WorkerThreadPool | --> 活跃线程数量 = {}, 核心线程数 = {}, 已完成任务数 = {}, 总任务数 = {}， 总Runnable数 = {}, threadName = {}"
             * ,
             * ((ThreadPoolExecutor) executorService).getActiveCount(),
             * ((ThreadPoolExecutor) executorService).getCorePoolSize(),
             * ((ThreadPoolExecutor) executorService).getCompletedTaskCount(),
             * ((ThreadPoolExecutor) executorService).getTaskCount(),
             * ((ThreadPoolExecutor) executorService).getQueue().size(),
             * Thread.currentThread().getName());
             */
        }
    }
}
