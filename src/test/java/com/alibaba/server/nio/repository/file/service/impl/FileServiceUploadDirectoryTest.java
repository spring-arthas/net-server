package com.alibaba.server.nio.repository.file.service.impl;

import com.alibaba.server.common.BasicConstant;
import com.alibaba.server.nio.core.server.BasicServer;
import com.alibaba.server.nio.repository.file.mapper.FileRepository;
import com.alibaba.server.nio.repository.file.repository.dataobject.FileDo;
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
                        if (current != null && update.getUserName() != null) {
                            current.setUserName(update.getUserName());
                        }
                        updates.add(update);
                        return null;
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
