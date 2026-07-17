package com.alibaba.server.nio.media;

import com.alibaba.server.nio.model.file.FileUploadFrame;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;

public class MediaStreamingIsolationTest {

    @Test
    public void chatReadHandlerDoesNotDependOnMediaBinaryControlFrames() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/alibaba/server/nio/handler/event/concret/ReadEventHandler.java")),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("BinaryControlFrameProcessor"));
    }

    @Test
    public void uploadFrameProtocolDoesNotAddHttpPlaybackControlTypes() {
        for (FileUploadFrame.FrameType type : FileUploadFrame.FrameType.values()) {
            assertFalse(type.name().contains("PLAYBACK"));
            assertFalse(type.name().contains("HTTP_STREAM"));
        }
    }
}
