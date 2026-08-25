package com.alibaba.server.nio.repository.file.service.impl;

import com.alibaba.server.common.BasicConstant;
import com.alibaba.server.nio.core.server.BasicServer;
import com.alibaba.server.nio.repository.file.mapper.FileRepository;
import com.alibaba.server.nio.repository.file.repository.dataobject.FileDo;
import com.alibaba.server.nio.repository.file.repository.param.FileDalQueryParam;
import com.alibaba.server.nio.repository.user.service.dto.UserDTO;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class FileServiceUploadDirectoryTest {

    private static final Integer USER_ID = 7;
    private static final String USER_NAME = "18806504525";

    private Path storageRoot;
    private Object previousStorageRoot;
    private Map<Long, FileDo> directories;
    private List<FileDo> updates;
    private FileServiceImpl fileService;

    @Before
    public void setUp() throws Exception {
        storageRoot = Files.createTempDirectory("upload-directory-test");
        previousStorageRoot = BasicServer.getMap().put(
                BasicConstant.NIO_FILE_BASE_PATH_LINUX_MAC, storageRoot.toString());
        directories = new java.util.concurrent.ConcurrentHashMap<>();
        updates = Collections.synchronizedList(new ArrayList<FileDo>());
        fileService = new FileServiceImpl();

        FileRepository repository = (FileRepository) Proxy.newProxyInstance(
                FileRepository.class.getClassLoader(),
                new Class<?>[] { FileRepository.class },
                (proxy, method, args) -> {
                    String methodName = method.getName();
                    if ("get".equals(methodName)) {
                        return directories.get((Long) args[0]);
                    }
                    if ("updateSelective".equals(methodName)) {
                        FileDo update = (FileDo) args[0];
                        FileDo current = directories.get(update.getId());
                        if (current != null && update.getFilePath() != null) {
                            current.setFilePath(update.getFilePath());
                        }
                        if (current != null && update.getFileName() != null) {
                            current.setFileName(update.getFileName());
                        }
                        if (current != null && update.getParentId() != null) {
                            current.setParentId(update.getParentId());
                        }
                        if (current != null && update.getHasChild() != null) {
                            current.setHasChild(update.getHasChild());
                        }
                        if (current != null && update.getUserName() != null) {
                            current.setUserName(update.getUserName());
                        }
                        updates.add(update);
                        return null;
                    }
                    if ("batchUpdateSelective".equals(methodName)) {
                        @SuppressWarnings("unchecked")
                        List<FileDo> batch = (List<FileDo>) args[0];
                        for (FileDo update : batch) {
                            FileDo current = directories.get(update.getId());
                            if (current != null && update.getFilePath() != null) {
                                current.setFilePath(update.getFilePath());
                            }
                            updates.add(update);
                        }
                        return null;
                    }
                    if ("getAssignFiles".equals(methodName)) {
                        FileDalQueryParam query = (FileDalQueryParam) args[0];
                        List<FileDo> result = new ArrayList<>();
                        for (FileDo item : directories.values()) {
                            if ((query.getParentId() == null || query.getParentId().equals(item.getParentId()))
                                    && (query.getFileName() == null || query.getFileName().equals(item.getFileName()))
                                    && (query.getIsFile() == null || query.getIsFile().equals(item.getIsFile()))
                                    && (query.getDel() == null || query.getDel().equals(item.getDel()))) {
                                result.add(item);
                            }
                        }
                        return result;
                    }
                    if ("toString".equals(methodName)) {
                        return "InMemoryFileRepository";
                    }
                    if (method.getReturnType().equals(boolean.class)) {
                        return false;
                    }
                    if (method.getReturnType().equals(long.class)) {
                        return 0L;
                    }
                    return null;
                });

        Field repositoryField = FileServiceImpl.class.getDeclaredField("fileRepository");
        repositoryField.setAccessible(true);
        repositoryField.set(fileService, repository);
    }

    @After
    public void tearDown() throws Exception {
        if (previousStorageRoot == null) {
            BasicServer.getMap().remove(BasicConstant.NIO_FILE_BASE_PATH_LINUX_MAC);
        } else {
            BasicServer.getMap().put(BasicConstant.NIO_FILE_BASE_PATH_LINUX_MAC, previousStorageRoot);
        }
        deleteRecursively(storageRoot);
    }

    @Test
    public void createsMissingHierarchyAndRepairsStaleDatabasePaths() throws Exception {
        addValidChain();

        String actual = fileService.ensureUploadDirectory(3L, USER_ID, USER_NAME);

        Path expected = storageRoot.resolve(USER_NAME).resolve("果果").resolve("日常");
        assertEquals(expected.toRealPath().toString(), actual);
        assertTrue(Files.isDirectory(expected));
        assertEquals(storageRoot.resolve(USER_NAME).toRealPath().toString(), directories.get(1L).getFilePath());
        assertEquals(storageRoot.resolve(USER_NAME).resolve("果果").toRealPath().toString(), directories.get(2L).getFilePath());
        assertEquals(expected.toRealPath().toString(), directories.get(3L).getFilePath());
        assertEquals(3, updates.size());
    }

    @Test
    public void acceptsAndRepairsLegacySystemOwnedUserRoot() throws Exception {
        addValidChain();
        directories.get(1L).setUserName("system");

        String actual = fileService.ensureUploadDirectory(3L, USER_ID, USER_NAME);

        assertEquals(storageRoot.resolve(USER_NAME).resolve("果果").resolve("日常").toRealPath().toString(), actual);
        assertEquals(USER_NAME, directories.get(1L).getUserName());
    }

    @Test
    public void rejectsBrokenDatabaseChainWithoutCreatingUserDirectory() throws Exception {
        FileDo target = directory(3L, 999L, "日常", USER_ID, USER_NAME);
        directories.put(target.getId(), target);

        assertRejected("目录层级数据不完整", () -> fileService.ensureUploadDirectory(3L, USER_ID, USER_NAME));

        assertFalse(Files.exists(storageRoot.resolve(USER_NAME)));
    }

    @Test
    public void rejectsDirectoryOwnedByAnotherUser() throws Exception {
        addValidChain();
        directories.get(2L).setUserId(99);

        assertRejected("目录不属于当前用户", () -> fileService.ensureUploadDirectory(3L, USER_ID, USER_NAME));
    }

    @Test
    public void rejectsDeletedOrMissingDirectoryRecord() throws Exception {
        addValidChain();
        directories.get(2L).setIsExist("N");

        assertRejected("目录状态无效", () -> fileService.ensureUploadDirectory(3L, USER_ID, USER_NAME));
    }

    @Test
    public void rejectsCyclicDatabaseDirectoryChain() throws Exception {
        FileDo parent = directory(2L, 3L, "果果", USER_ID, USER_NAME);
        FileDo target = directory(3L, 2L, "日常", USER_ID, USER_NAME);
        directories.put(parent.getId(), parent);
        directories.put(target.getId(), target);

        assertRejected("目录层级存在循环引用", () -> fileService.ensureUploadDirectory(3L, USER_ID, USER_NAME));
    }

    @Test
    public void rejectsUnsafeDatabaseDirectoryName() throws Exception {
        addValidChain();
        directories.get(2L).setFileName("../escape");

        assertRejected("目录名称不安全", () -> fileService.ensureUploadDirectory(3L, USER_ID, USER_NAME));
        assertFalse(Files.exists(storageRoot.resolve("escape")));
    }

    @Test
    public void rejectsFileOccupyingExpectedDirectoryPath() throws Exception {
        addValidChain();
        Files.createDirectories(storageRoot.resolve(USER_NAME));
        Files.createFile(storageRoot.resolve(USER_NAME).resolve("果果"));

        assertRejected("目录路径被文件占用", () -> fileService.ensureUploadDirectory(3L, USER_ID, USER_NAME));
    }

    @Test
    public void rejectsSymbolicLinkInExpectedDirectoryChain() throws Exception {
        addValidChain();
        Path outside = Files.createTempDirectory("upload-directory-outside");
        try {
            Files.createDirectories(storageRoot.resolve(USER_NAME));
            Files.createSymbolicLink(storageRoot.resolve(USER_NAME).resolve("果果"), outside);

            assertRejected("目录路径不允许包含符号链接",
                    () -> fileService.ensureUploadDirectory(3L, USER_ID, USER_NAME));
        } finally {
            deleteRecursively(outside);
        }
    }

    @Test
    public void serializesConcurrentRecoveryAndReleasesDirectoryLock() throws Exception {
        addValidChain();
        ExecutorService executor = Executors.newFixedThreadPool(6);
        List<Callable<String>> calls = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            calls.add(() -> fileService.ensureUploadDirectory(3L, USER_ID, USER_NAME));
        }
        try {
            List<Future<String>> futures = executor.invokeAll(calls);
            String expected = storageRoot.resolve(USER_NAME).resolve("果果").resolve("日常").toRealPath().toString();
            for (Future<String> future : futures) {
                assertEquals(expected, future.get());
            }
        } finally {
            executor.shutdownNow();
        }

        Field locksField = FileServiceImpl.class.getDeclaredField("uploadDirectoryLocks");
        locksField.setAccessible(true);
        Map<?, ?> locks = (Map<?, ?>) locksField.get(fileService);
        assertTrue(locks.isEmpty());
    }

    @Test
    public void renamingDirectoryUpdatesDescendantVideoPathsBeforeReturning() throws Exception {
        Path oldDirectory = storageRoot.resolve(USER_NAME).resolve("旧目录");
        Path nestedDirectory = oldDirectory.resolve("子目录");
        Path video = nestedDirectory.resolve("movie.mp4");
        Files.createDirectories(nestedDirectory);
        Files.write(video, new byte[] { 1, 2, 3 });

        directories.put(1L, directory(1L, -1L, USER_NAME, USER_ID, USER_NAME));
        directories.put(2L, directory(2L, 1L, "旧目录", USER_ID, USER_NAME));
        directories.put(3L, directory(3L, 2L, "子目录", USER_ID, USER_NAME));
        FileDo videoRecord = directory(4L, 3L, "movie.mp4", USER_ID, USER_NAME);
        videoRecord.setIsFile("Y");
        directories.put(4L, videoRecord);
        directories.get(1L).setFilePath(storageRoot.resolve(USER_NAME).toString());
        directories.get(2L).setFilePath(oldDirectory.toString());
        // 模拟历史重命名后层级名称已更新、但目录自身 file_path 仍指向真实物理目录的情况。
        directories.get(2L).setFileName("数据库旧名称");
        directories.get(3L).setFilePath(nestedDirectory.toString());
        videoRecord.setFilePath(video.toString());

        fileService.updateDirectory(2L, "新目录");

        Path renamedVideo = storageRoot.resolve(USER_NAME).resolve("新目录").resolve("子目录").resolve("movie.mp4");
        assertTrue(Files.isRegularFile(renamedVideo));
        assertEquals(storageRoot.resolve(USER_NAME).resolve("新目录").toString(), directories.get(2L).getFilePath());
        assertEquals(storageRoot.resolve(USER_NAME).resolve("新目录").resolve("子目录").toString(), directories.get(3L).getFilePath());
        assertEquals(renamedVideo.toString(), directories.get(4L).getFilePath());
    }

    @Test
    public void movingFileMovesPhysicalFileAndUpdatesDatabaseParentAndPath() throws Exception {
        addValidChain();
        FileDo destination = directory(4L, 1L, "归档", USER_ID, USER_NAME);
        directories.put(destination.getId(), destination);
        Path sourceDirectory = storageRoot.resolve(USER_NAME).resolve("果果").resolve("日常");
        Path source = sourceDirectory.resolve("task-1_movie.mp4");
        Files.createDirectories(sourceDirectory);
        Files.write(source, new byte[] { 1, 2, 3 });
        FileDo file = directory(5L, 3L, "movie.mp4", USER_ID, USER_NAME);
        file.setIsFile("Y");
        file.setFilePath(source.toString());
        directories.put(file.getId(), file);

        fileService.moveFile(5L, 4L, user());

        Path destinationFile = storageRoot.resolve(USER_NAME).resolve("归档").resolve("task-1_movie.mp4");
        assertFalse(Files.exists(source));
        assertTrue(Files.isRegularFile(destinationFile));
        assertEquals(Long.valueOf(4L), directories.get(5L).getParentId());
        assertEquals(destinationFile.toString(), directories.get(5L).getFilePath());
    }

    @Test
    public void movingFileRejectsAFileOwnedByAnotherUserWithoutTouchingDisk() throws Exception {
        addValidChain();
        Path sourceDirectory = storageRoot.resolve(USER_NAME).resolve("果果").resolve("日常");
        Path source = sourceDirectory.resolve("task-1_movie.mp4");
        Files.createDirectories(sourceDirectory);
        Files.write(source, new byte[] { 1, 2, 3 });
        FileDo file = directory(5L, 3L, "movie.mp4", 99, "other-user");
        file.setIsFile("Y");
        file.setFilePath(source.toString());
        directories.put(file.getId(), file);

        assertRejected("文件不属于当前用户", () -> fileService.moveFile(5L, 2L, user()));
        assertTrue(Files.isRegularFile(source));
        assertEquals(Long.valueOf(3L), directories.get(5L).getParentId());
    }

    @Test
    public void movingDirectoryUpdatesDescendantPathsAndBothParentStatesBeforeReturning() throws Exception {
        Path sourceParentDirectory = storageRoot.resolve(USER_NAME).resolve("原父目录");
        Path sourceDirectory = sourceParentDirectory.resolve("源目录");
        Path nestedDirectory = sourceDirectory.resolve("子目录");
        Path video = nestedDirectory.resolve("movie.mp4");
        Path targetDirectory = storageRoot.resolve(USER_NAME).resolve("目标目录");
        Files.createDirectories(nestedDirectory);
        Files.createDirectories(targetDirectory);
        Files.write(video, new byte[] { 1, 2, 3 });

        directories.put(1L, directory(1L, -1L, USER_NAME, USER_ID, USER_NAME));
        directories.put(6L, directory(6L, 1L, "原父目录", USER_ID, USER_NAME));
        directories.put(2L, directory(2L, 6L, "源目录", USER_ID, USER_NAME));
        directories.put(3L, directory(3L, 2L, "子目录", USER_ID, USER_NAME));
        directories.put(5L, directory(5L, 1L, "目标目录", USER_ID, USER_NAME));
        FileDo videoRecord = directory(4L, 3L, "movie.mp4", USER_ID, USER_NAME);
        videoRecord.setIsFile("Y");
        directories.put(4L, videoRecord);
        directories.get(1L).setFilePath(storageRoot.resolve(USER_NAME).toString());
        directories.get(6L).setFilePath(sourceParentDirectory.toString());
        directories.get(2L).setFilePath(sourceDirectory.toString());
        directories.get(3L).setFilePath(nestedDirectory.toString());
        directories.get(5L).setFilePath(targetDirectory.toString());
        videoRecord.setFilePath(video.toString());

        fileService.moveDirectory(2L, 5L);

        Path movedVideo = targetDirectory.resolve("源目录").resolve("子目录").resolve("movie.mp4");
        assertTrue(Files.isRegularFile(movedVideo));
        assertFalse(Files.exists(sourceDirectory));
        assertEquals(Long.valueOf(5L), directories.get(2L).getParentId());
        assertEquals(targetDirectory.resolve("源目录").toString(), directories.get(2L).getFilePath());
        assertEquals(targetDirectory.resolve("源目录").resolve("子目录").toString(), directories.get(3L).getFilePath());
        assertEquals(movedVideo.toString(), directories.get(4L).getFilePath());
        assertEquals("N", directories.get(6L).getHasChild());
        assertEquals("Y", directories.get(5L).getHasChild());
    }

    @Test
    public void movingFileUpdatesPhysicalPathDatabaseParentAndBothParentStates() throws Exception {
        Path sourceDirectory = storageRoot.resolve(USER_NAME).resolve("源目录");
        Path targetDirectory = storageRoot.resolve(USER_NAME).resolve("目标目录");
        Path sourceFile = sourceDirectory.resolve("upload-task-123.bin");
        Files.createDirectories(sourceDirectory);
        Files.createDirectories(targetDirectory);
        Files.write(sourceFile, new byte[] { 4, 5, 6 });

        directories.put(1L, directory(1L, -1L, USER_NAME, USER_ID, USER_NAME));
        directories.put(2L, directory(2L, 1L, "源目录", USER_ID, USER_NAME));
        directories.put(3L, directory(3L, 1L, "目标目录", USER_ID, USER_NAME));
        FileDo file = directory(4L, 2L, "说明.txt", USER_ID, USER_NAME);
        file.setIsFile("Y");
        file.setFilePath(sourceFile.toString());
        directories.put(4L, file);
        directories.get(1L).setFilePath(storageRoot.resolve(USER_NAME).toString());
        directories.get(2L).setFilePath(sourceDirectory.toString());
        directories.get(3L).setFilePath(targetDirectory.toString());

        fileService.moveFile(4L, 3L);

        Path movedFile = targetDirectory.resolve("upload-task-123.bin");
        assertTrue(Files.isRegularFile(movedFile));
        assertFalse(Files.exists(sourceFile));
        assertEquals(Long.valueOf(3L), file.getParentId());
        assertEquals(movedFile.toString(), file.getFilePath());
        assertEquals("说明.txt", file.getFileName());
        assertEquals("N", directories.get(2L).getHasChild());
        assertEquals("Y", directories.get(3L).getHasChild());
    }

    private void addValidChain() {
        directories.put(1L, directory(1L, -1L, USER_NAME, USER_ID, USER_NAME));
        directories.put(2L, directory(2L, 1L, "果果", USER_ID, USER_NAME));
        directories.put(3L, directory(3L, 2L, "日常", USER_ID, USER_NAME));
        directories.get(1L).setFilePath("/stale/root");
        directories.get(2L).setFilePath("/stale/parent");
        directories.get(3L).setFilePath("/stale/target");
    }

    private FileDo directory(Long id, Long parentId, String name, Integer userId, String userName) {
        FileDo directory = new FileDo();
        directory.setId(id);
        directory.setParentId(parentId);
        directory.setFileName(name);
        directory.setUserId(userId);
        directory.setUserName(userName);
        directory.setIsFile("N");
        directory.setIsExist("Y");
        directory.setDel("N");
        return directory;
    }

    private UserDTO user() {
        UserDTO user = new UserDTO();
        user.setId(USER_ID.longValue());
        user.setUserName(USER_NAME);
        return user;
    }

    private void assertRejected(String message, ThrowingRunnable runnable) throws Exception {
        try {
            runnable.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue("actual message: " + expected.getMessage(), expected.getMessage().contains(message));
        }
    }

    private void deleteRecursively(Path path) throws Exception {
        if (path == null || !Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(path)) {
            try (java.nio.file.DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
                for (Path child : children) {
                    deleteRecursively(child);
                }
            }
        }
        Files.deleteIfExists(path);
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
