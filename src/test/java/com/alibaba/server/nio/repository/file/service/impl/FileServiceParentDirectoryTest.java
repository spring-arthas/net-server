package com.alibaba.server.nio.repository.file.service.impl;

import com.alibaba.server.nio.repository.file.service.dto.FileDto;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class FileServiceParentDirectoryTest {

    @Test
    public void shouldApplyDirectParentDirectoryNameToFileListDto() {
        FileDto file = new FileDto();
        file.setParentId(7428297864713543680L);
        Map<Long, String> parentNames = new HashMap<>();
        parentNames.put(7428297864713543680L, "欧美");

        FileServiceImpl.applyParentDirectoryNames(Collections.singletonList(file), parentNames);

        assertEquals("欧美", file.getParentDirName());
    }
}
