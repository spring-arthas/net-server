package com.alibaba.server.nio.media;

import com.alibaba.server.nio.repository.file.service.dto.FileDto;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SafeFileResolverTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void resolvesRelativePathUnderConfiguredRoot() throws Exception {
        File root = temporaryFolder.newFolder("storage");
        SafeFileResolver resolver = new SafeFileResolver(root.getAbsolutePath());

        File resolved = resolver.resolve(fileDto("videos/demo.mp4"));

        assertEquals(new File(root, "videos/demo.mp4").getCanonicalFile(), resolved.getCanonicalFile());
    }

    @Test(expected = java.io.IOException.class)
    public void rejectsRelativePathEscapingConfiguredRoot() throws Exception {
        File root = temporaryFolder.newFolder("storage");
        SafeFileResolver resolver = new SafeFileResolver(root.getAbsolutePath());

        resolver.resolve(fileDto("../secret.mp4"));
    }

    @Test
    public void doesNotReturnAbsolutePathOutsideConfiguredRoot() throws Exception {
        File root = temporaryFolder.newFolder("storage");
        File outside = temporaryFolder.newFile("outside.mp4");
        SafeFileResolver resolver = new SafeFileResolver(root.getAbsolutePath());

        File resolved = resolver.resolve(fileDto(outside.getAbsolutePath()));

        assertTrue(resolved.getCanonicalPath().startsWith(root.getCanonicalPath()));
    }

    private FileDto fileDto(String filePath) {
        FileDto fileDto = new FileDto();
        fileDto.setFilePath(filePath);
        return fileDto;
    }
}
