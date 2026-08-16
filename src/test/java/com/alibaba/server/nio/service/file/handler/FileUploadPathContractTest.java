package com.alibaba.server.nio.service.file.handler;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FileUploadPathContractTest {

    @Test
    public void handlerAndContextUseTheCentralSafePathResolver() throws Exception {
        String handler = read(
                "src/main/java/com/alibaba/server/nio/service/file/handler/FileUploadHandler.java");
        String context = read(
                "src/main/java/com/alibaba/server/nio/model/file/FileUploadContext.java");

        assertTrue(handler.contains("UploadPathResolver.resolve"));
        assertTrue(context.contains("UploadPathResolver.resolve"));
        assertFalse(handler.contains("request.getTaskId() + \"_\" + request.getFileName()"));
        assertFalse(handler.contains("fileUploadRequest.getTaskId() + \"_\" + fileUploadRequest.getFileName()"));
    }

    private String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }
}
