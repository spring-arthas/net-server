package com.alibaba.server.nio.service.file.handler;

import com.alibaba.server.nio.model.file.FileUploadContext;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class FileUploadHandlerResponseTaskIdTest {

    @Test
    public void shouldUseRequestTaskIdWhenUploadContextIsNotCreated() {
        assertEquals("client-task-id", UploadResponseTaskIdResolver.resolve(null, "client-task-id"));
    }

    @Test
    public void shouldPreferUploadContextTaskIdWhenContextExists() {
        FileUploadContext uploadContext = new FileUploadContext();
        uploadContext.setRequestTaskId("context-task-id");

        assertEquals(
                "context-task-id",
                UploadResponseTaskIdResolver.resolve(uploadContext, "client-task-id"));
    }
}
