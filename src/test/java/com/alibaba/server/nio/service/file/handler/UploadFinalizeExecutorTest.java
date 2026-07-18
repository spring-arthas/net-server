package com.alibaba.server.nio.service.file.handler;

import org.junit.Test;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertTrue;

public class UploadFinalizeExecutorTest {

    @Test
    public void shouldRunFinalizationOutsideUploadWorkerPool() throws Exception {
        Future<String> future = UploadFinalizeExecutor.submit(
                () -> Thread.currentThread().getName());

        assertTrue(future.get(5, TimeUnit.SECONDS).startsWith("UPLOAD_FINALIZE_"));
    }
}
