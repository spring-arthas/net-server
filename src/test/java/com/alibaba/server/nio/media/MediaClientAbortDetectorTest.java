package com.alibaba.server.nio.media;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MediaClientAbortDetectorTest {

    @Test
    public void recognizesClientAbortMessagesAcrossCauseAndSuppressedExceptions() {
        IOException brokenPipe = new IOException("Broken pipe");
        IOException wrapped = new IOException("response close failed", brokenPipe);
        assertTrue(MediaClientAbortDetector.isClientAbort(wrapped));

        IOException responseFailure = new IOException("write failed");
        responseFailure.addSuppressed(new IOException("insufficient bytes written to stream"));
        assertTrue(MediaClientAbortDetector.isClientAbort(responseFailure));

        assertTrue(MediaClientAbortDetector.isClientAbort(new IOException("Connection reset by peer")));
        assertTrue(MediaClientAbortDetector.isClientAbort(new IOException("Stream closed")));
    }

    @Test
    public void leavesRealIoFailuresVisible() {
        assertFalse(MediaClientAbortDetector.isClientAbort(new IOException("Permission denied")));
        assertFalse(MediaClientAbortDetector.isClientAbort(new IOException("Input/output error")));
        assertFalse(MediaClientAbortDetector.isClientAbort(null));
    }
}
