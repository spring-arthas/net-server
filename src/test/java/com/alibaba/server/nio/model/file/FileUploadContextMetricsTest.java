package com.alibaba.server.nio.model.file;

import org.junit.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;

public class FileUploadContextMetricsTest {

    @Test
    public void shouldAccumulateAndResetAckWindowWriteMetrics() {
        FileUploadContext context = new FileUploadContext();

        context.recordWindowWrite(65_536, TimeUnit.MILLISECONDS.toNanos(12));
        context.recordWindowWrite(32_768, TimeUnit.MILLISECONDS.toNanos(8));

        assertEquals(98_304L, context.getWindowBytesWritten());
        assertEquals(20L, context.getWindowWriteMillis());

        context.resetWindowMetrics();

        assertEquals(0L, context.getWindowBytesWritten());
        assertEquals(0L, context.getWindowWriteMillis());
    }

    @Test
    public void shouldExposeFinalizingStateBeforeIntegrityCheckCompletes() {
        FileUploadContext context = new FileUploadContext();

        context.markFinalizing();

        assertEquals(FileUploadContext.UploadStatus.FINALIZING, context.getStatus());
    }
}
