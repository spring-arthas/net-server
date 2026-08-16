package com.alibaba.server.nio.service.file.handler;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;

public class FileTransferSensitiveLogContractTest {

    @Test
    public void transferHandlersNeverLogCompleteRequestPayloads() throws Exception {
        String uploadSource = read(
                "src/main/java/com/alibaba/server/nio/service/file/handler/FileUploadHandler.java");
        String downloadSource = read(
                "src/main/java/com/alibaba/server/nio/service/file/handler/FileDownloadHandler.java");

        assertFalse(uploadSource.contains("JSON.toJSONString(fileUploadRequest)"));
        assertFalse(downloadSource.contains("入参 = {}\", jsonData"));
    }

    private String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }
}
