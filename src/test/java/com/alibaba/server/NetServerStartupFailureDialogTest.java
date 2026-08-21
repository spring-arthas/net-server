package com.alibaba.server;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

public class NetServerStartupFailureDialogTest {

    @Test
    public void macApplicationShowsVisibleStartupFailureDialog() throws Exception {
        String source = new String(
                Files.readAllBytes(Paths.get("src/main/java/com/alibaba/server/NetServer.java")),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("osascript"));
        assertTrue(source.contains("Address already in use"));
        assertTrue(source.contains("Net Server 启动失败"));
    }
}
