package com.alibaba.server.nio.service.file.checkpoint;

import com.alibaba.server.nio.model.file.UploadCheckpoint;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class CheckpointManagerUserIsolationTest {

    @After
    public void tearDown() {
        CheckpointManager.clearAll();
    }

    @Test
    public void shouldIsolateSameMd5ByUserId() {
        CheckpointManager.saveCheckpoint(checkpoint(1001, "task-a"));
        CheckpointManager.saveCheckpoint(checkpoint(1002, "task-b"));

        assertEquals("task-a", CheckpointManager.getCheckpoint("same-md5", 1001).getRequestTaskId());
        assertEquals("task-b", CheckpointManager.getCheckpoint("same-md5", 1002).getRequestTaskId());

        CheckpointManager.removeCheckpoint("same-md5", 1001);

        assertNull(CheckpointManager.getCheckpoint("same-md5", 1001));
        assertNotNull(CheckpointManager.getCheckpoint("same-md5", 1002));
    }

    private UploadCheckpoint checkpoint(int userId, String taskId) {
        UploadCheckpoint checkpoint = new UploadCheckpoint();
        checkpoint.setMd5("same-md5");
        checkpoint.setUserId(userId);
        checkpoint.setRequestTaskId(taskId);
        return checkpoint;
    }
}
