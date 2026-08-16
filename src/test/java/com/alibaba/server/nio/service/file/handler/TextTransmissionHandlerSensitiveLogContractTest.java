package com.alibaba.server.nio.service.file.handler;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;

public class TextTransmissionHandlerSensitiveLogContractTest {

    @Test
    public void frameLogsNeverSerializeRequestOrResponsePayloads() throws Exception {
        Path sourcePath = Paths.get(
                "src/main/java/com/alibaba/server/nio/service/file/handler/TextTransmissionHandler.java");
        String source = new String(Files.readAllBytes(sourcePath), StandardCharsets.UTF_8);

        assertFalse(source.contains("JSON.toJSONString(frame.getDataAsString())"));
        assertFalse(source.contains("JSON.toJSONString(data), type.getDescription()"));
    }
}
