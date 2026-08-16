package com.alibaba.server.nio.service.file.security;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class UploadPathResolverTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void validUploadPathStaysDirectlyInsideAuthenticatedDirectory() throws Exception {
        Path directory = temporaryFolder.newFolder("alice-drive").toPath();

        Path target = UploadPathResolver.resolve(directory, "ABC-123", "旅行 照片.jpg");

        assertEquals(directory.toRealPath(), target.getParent());
        assertEquals("ABC-123_旅行 照片.jpg", target.getFileName().toString());
        assertTrue(target.startsWith(directory.toRealPath()));
    }

    @Test
    public void rejectsTraversalAbsoluteSeparatorsAndControlCharacters() throws Exception {
        Path directory = temporaryFolder.newFolder("safe-root").toPath();
        String[] invalidFileNames = {
                "../outside.txt",
                "/tmp/outside.txt",
                "nested/file.txt",
                "nested\\file.txt",
                "..",
                "bad\u0000name.txt",
                "bad\nname.txt"
        };
        String[] invalidTaskIds = {
                "../task",
                "/absolute",
                "task/child",
                "task\\child",
                "task id",
                "task\nline"
        };

        for (String fileName : invalidFileNames) {
            assertRejected(directory, "safe-task", fileName);
        }
        for (String taskId : invalidTaskIds) {
            assertRejected(directory, taskId, "report.txt");
        }
    }

    private void assertRejected(Path directory, String taskId, String fileName) {
        try {
            UploadPathResolver.resolve(directory, taskId, fileName);
            fail("非法上传路径未被拒绝: taskId=" + taskId + ", fileName=" + fileName);
        } catch (IllegalArgumentException expected) {
            // 预期：所有客户端路径片段都在落盘前拒绝。
        }
    }
}
