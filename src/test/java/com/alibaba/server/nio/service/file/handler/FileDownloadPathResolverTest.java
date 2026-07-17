package com.alibaba.server.nio.service.file.handler;

import com.alibaba.server.nio.repository.file.service.dto.FileDto;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FileDownloadPathResolverTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void resolvesExistingFileByNameWhenDatabasePathIsBlank() throws Exception {
        File storageRoot = temporaryFolder.newFolder("storages");
        File dateFolder = new File(storageRoot, "2026/07/02");
        Files.createDirectories(dateFolder.toPath());

        File storedFile = new File(dateFolder, "task_chat-preview-photo.jpg");
        Files.write(storedFile.toPath(), new byte[] { 1, 2, 3, 4 });

        FileDto fileDto = new FileDto();
        fileDto.setFileName("chat-preview-photo.jpg");
        fileDto.setFilePath(null);
        fileDto.setFileSize(4L);

        File resolved = FileDownloadPathResolver.resolve(fileDto, null, storageRoot.getAbsolutePath());

        assertEquals(storedFile.getCanonicalFile(), resolved.getCanonicalFile());
    }

    @Test
    public void prefersDatabasePathWhenItExists() throws Exception {
        File storageRoot = temporaryFolder.newFolder("storages");
        File storedFile = new File(storageRoot, "direct.jpg");
        Files.write(storedFile.toPath(), new byte[] { 1, 2 });

        FileDto fileDto = new FileDto();
        fileDto.setFileName("direct.jpg");
        fileDto.setFilePath(storedFile.getAbsolutePath());
        fileDto.setFileSize(2L);

        File resolved = FileDownloadPathResolver.resolve(fileDto, null, storageRoot.getAbsolutePath());

        assertEquals(storedFile.getCanonicalFile(), resolved.getCanonicalFile());
    }

    @Test
    public void rejectsDatabasePathWhenStoredSizeDiffersFromDatabaseSize() throws Exception {
        File storageRoot = temporaryFolder.newFolder("storages");
        File storedFile = new File(storageRoot, "preview.jpg");
        Files.write(storedFile.toPath(), new byte[] { 1, 2, 3, 4 });

        FileDto fileDto = new FileDto();
        fileDto.setFileName("preview.jpg");
        fileDto.setFilePath(storedFile.getAbsolutePath());
        fileDto.setFileSize(2L);

        File resolved = FileDownloadPathResolver.resolve(fileDto, null, storageRoot.getAbsolutePath());

        assertTrue(resolved == null);
    }

    @Test
    public void rejectsStoredFileNameWhenSizeDiffersFromDatabaseSize() throws Exception {
        File storageRoot = temporaryFolder.newFolder("storages");
        File dateFolder = new File(storageRoot, "2026/07/02");
        Files.createDirectories(dateFolder.toPath());

        File storedFile = new File(dateFolder, "task_chat-preview-size-mismatch.jpg");
        Files.write(storedFile.toPath(), new byte[] { 1, 2, 3, 4 });

        FileDto fileDto = new FileDto();
        fileDto.setFileName("chat-preview-size-mismatch.jpg");
        fileDto.setFilePath(null);
        fileDto.setFileSize(2L);

        File resolved = FileDownloadPathResolver.resolve(fileDto, null, storageRoot.getAbsolutePath());

        assertTrue(resolved == null);
    }

    @Test
    public void rejectsRequestedAbsolutePathOutsideStorageRoot() throws Exception {
        File storageRoot = temporaryFolder.newFolder("storages");
        File outsideFile = temporaryFolder.newFile("outside-secret.txt");
        Files.write(outsideFile.toPath(), new byte[] { 1, 2, 3 });

        FileDto fileDto = new FileDto();
        fileDto.setFileName("missing.txt");
        fileDto.setFilePath(null);
        fileDto.setFileSize(3L);

        File resolved = FileDownloadPathResolver.resolve(fileDto, outsideFile.getAbsolutePath(),
                storageRoot.getAbsolutePath());

        assertTrue(resolved == null);
    }

    @Test
    public void rejectsAmbiguousSameNameFallback() throws Exception {
        File storageRoot = temporaryFolder.newFolder("storages");
        File firstFolder = new File(storageRoot, "2026/07/01");
        File secondFolder = new File(storageRoot, "2026/07/02");
        Files.createDirectories(firstFolder.toPath());
        Files.createDirectories(secondFolder.toPath());
        Files.write(new File(firstFolder, "task-a_photo.jpg").toPath(), new byte[] { 1, 2, 3 });
        Files.write(new File(secondFolder, "task-b_photo.jpg").toPath(), new byte[] { 1, 2, 3 });

        FileDto fileDto = new FileDto();
        fileDto.setFileName("photo.jpg");
        fileDto.setFilePath(null);
        fileDto.setFileSize(3L);

        File resolved = FileDownloadPathResolver.resolve(fileDto, null, storageRoot.getAbsolutePath());

        assertTrue(resolved == null);
    }

    @Test
    public void downloadHandlerUsesSharedWriteQueueInsteadOfDirectChannelWrites() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/alibaba/server/nio/service/file/handler/FileDownloadHandler.java")));

        assertTrue(source.contains("WriteQueueHelper.submitWrite"));
        assertFalse(source.contains("getSocketChannel().write("));
    }
}
