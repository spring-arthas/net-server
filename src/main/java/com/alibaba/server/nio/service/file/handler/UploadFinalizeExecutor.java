package com.alibaba.server.nio.service.file.handler;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 上传完成校验专用线程池，避免大文件 MD5 占用通用文件 Worker。
 */
public final class UploadFinalizeExecutor {

    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger(0);
    private static final int THREAD_COUNT = Math.max(
            2, Runtime.getRuntime().availableProcessors() / 2);
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(
            THREAD_COUNT,
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(
                            runnable,
                            "UPLOAD_FINALIZE_" + THREAD_SEQUENCE.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }
            });

    private UploadFinalizeExecutor() {
    }

    public static Future<?> submit(Runnable task) {
        return EXECUTOR.submit(task);
    }

    public static <T> Future<T> submit(Callable<T> task) {
        return EXECUTOR.submit(task);
    }
}
