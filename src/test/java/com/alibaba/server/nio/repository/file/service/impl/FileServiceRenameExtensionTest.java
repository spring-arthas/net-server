package com.alibaba.server.nio.repository.file.service.impl;

import com.alibaba.server.nio.repository.file.repository.dataobject.FileDo;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class FileServiceRenameExtensionTest {

    @Test
    public void bareRenameKeepsExtensionFromPhysicalPath() {
        FileDo file = new FileDo();
        file.setFileName("旧名称");
        file.setFilePath("/storage/task_original.mp4");

        assertEquals("新名称.mp4",
                FileServiceImpl.preserveOriginalFileExtension(file, "新名称"));
    }

    @Test
    public void differentRequestedExtensionIsReplaced() {
        FileDo file = new FileDo();
        file.setFileName("旧名称.mp4");

        assertEquals("新名称.mp4",
                FileServiceImpl.preserveOriginalFileExtension(file, "新名称.avi"));
    }

    @Test
    public void stablePhysicalPathRepairsAnAlreadyWrongDisplayExtension() {
        FileDo file = new FileDo();
        file.setFileName("旧名称.avi");
        file.setFilePath("/storage/task_original.mp4");

        assertEquals("新名称.mp4",
                FileServiceImpl.preserveOriginalFileExtension(file, "新名称"));
    }

    @Test
    public void existingOriginalExtensionIsNotDuplicated() {
        FileDo file = new FileDo();
        file.setFileName("旧名称.mp4");

        assertEquals("新名称.mp4",
                FileServiceImpl.preserveOriginalFileExtension(file, "新名称.mp4"));
    }
}
