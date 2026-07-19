package com.alibaba.server.nio.repository.file.service.impl;

import com.alibaba.server.common.BasicConstant;
import com.alibaba.server.nio.core.server.BasicServer;
import com.alibaba.server.nio.repository.file.mapper.FileRepository;
import com.alibaba.server.nio.repository.file.repository.dataobject.FileDo;
import com.alibaba.server.nio.repository.file.repository.param.FileDalQueryParam;
import com.alibaba.server.nio.repository.file.service.exception.DirectoryContainsFileException;
import com.alibaba.server.nio.repository.user.service.dto.UserDTO;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class FileServiceDirectoryDeleteTest {

    private static final Integer USER_ID = 7;
    private static final String USER_NAME = "18806504525";

    private Path storageRoot;
    private Object previousStorageRoot;
    private Map<Long, FileDo> records;
    private FileServiceImpl fileService;
    private UserDTO user;

    @Before
    public void setUp() throws Exception {
        storageRoot = Files.createTempDirectory("directory-delete-test");
        previousStorageRoot = BasicServer.getMap().put(
                BasicConstant.NIO_FILE_BASE_PATH_LINUX_MAC, storageRoot.toString());
        records = new LinkedHashMap<>();
        fileService = new FileServiceImpl();
        user = new UserDTO();
        user.setId(USER_ID.longValue());
        user.setUserName(USER_NAME);

        FileRepository repository = (FileRepository) Proxy.newProxyInstance(
                FileRepository.class.getClassLoader(),
                new Class<?>[] { FileRepository.class },
                (proxy, method, args) -> {
                    String methodName = method.getName();
                    if ("get".equals(methodName)) {
                        return records.get((Long) args[0]);
                    }
                    if ("getAssignFiles".equals(methodName)) {
                        return findRecords((FileDalQueryParam) args[0]);
                    }
                    if ("batchLogicDelete".equals(methodName)) {
                        @SuppressWarnings("unchecked")
                        List<Long> ids = (List<Long>) args[0];
                        for (Long id : ids) {
                            FileDo record = records.get(id);
                            if (record != null) {
                                record.setDel("Y");
                            }
                        }
                        return null;
                    }
                    if ("updateSelective".equals(methodName)) {
                        FileDo update = (FileDo) args[0];
                        FileDo current = records.get(update.getId());
                        if (current != null && update.getHasChild() != null) {
                            current.setHasChild(update.getHasChild());
                        }
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
    public void rejectsDirectoryTreeContainingValidFile() throws Exception {
        addDirectoryTree();
        FileDo validFile = file(4L, 3L, "report.pdf", "Y");
        records.put(validFile.getId(), validFile);
        Path target = createTargetTree();
        Files.write(target.resolve("empty-child").resolve("report.pdf"),
                "content".getBytes(StandardCharsets.UTF_8));

        try {
            fileService.deleteDirectory(2L, user);
            fail("expected DirectoryContainsFileException");
        } catch (DirectoryContainsFileException expected) {
            assertTrue(expected.getMessage().contains("存在有效文件"));
        }

        assertTrue(Files.exists(target));
        assertEquals("N", records.get(2L).getDel());
        assertEquals("N", records.get(3L).getDel());
        assertEquals("N", records.get(4L).getDel());
    }

    @Test
    public void deletesEmptyDirectoryTreeAndDatabaseRecords() throws Exception {
        addDirectoryTree();
        Path target = createTargetTree();
        Files.write(target.resolve("empty-child").resolve(".DS_Store"), new byte[] { 1, 2, 3 });

        assertTrue(fileService.deleteDirectory(2L, user));

        assertFalse(Files.exists(target));
        assertEquals("Y", records.get(2L).getDel());
        assertEquals("Y", records.get(3L).getDel());
        assertEquals("N", records.get(1L).getHasChild());
    }

    @Test
    public void deletesInvalidFileRecordWithDirectoryTree() throws Exception {
        addDirectoryTree();
        FileDo invalidFile = file(4L, 3L, "unfinished.part", "N");
        records.put(invalidFile.getId(), invalidFile);
        Path target = createTargetTree();
        Files.write(target.resolve("empty-child").resolve("unfinished.part"), new byte[] { 1 });

        assertTrue(fileService.deleteDirectory(2L, user));

        assertFalse(Files.exists(target));
        assertEquals("Y", records.get(4L).getDel());
    }

    @Test
    public void rejectsDirectoryOwnedByAnotherUser() throws Exception {
        addDirectoryTree();
        records.get(2L).setUserId(99);

        try {
            fileService.deleteDirectory(2L, user);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("目录不属于当前用户"));
        }
    }

    private void addDirectoryTree() {
        records.put(1L, directory(1L, -1L, USER_NAME));
        records.put(2L, directory(2L, 1L, "target"));
        records.put(3L, directory(3L, 2L, "empty-child"));
        records.get(1L).setHasChild("Y");
        records.get(2L).setHasChild("Y");
    }

    private Path createTargetTree() throws Exception {
        Path target = storageRoot.resolve(USER_NAME).resolve("target");
        Files.createDirectories(target.resolve("empty-child"));
        return target;
    }

    private List<FileDo> findRecords(FileDalQueryParam param) {
        List<FileDo> matches = new ArrayList<>();
        for (FileDo record : records.values()) {
            if (param.getParentId() != null && !param.getParentId().equals(record.getParentId())) {
                continue;
            }
            if (param.getDel() != null && !param.getDel().equals(record.getDel())) {
                continue;
            }
            matches.add(record);
        }
        return matches;
    }

    private FileDo directory(Long id, Long parentId, String name) {
        FileDo directory = new FileDo();
        directory.setId(id);
        directory.setParentId(parentId);
        directory.setFileName(name);
        directory.setFileType("NOT_FILE");
        directory.setUserId(USER_ID);
        directory.setUserName(USER_NAME);
        directory.setIsFile("N");
        directory.setIsExist("Y");
        directory.setHasChild("N");
        directory.setDel("N");
        return directory;
    }

    private FileDo file(Long id, Long parentId, String name, String isExist) {
        FileDo file = new FileDo();
        file.setId(id);
        file.setParentId(parentId);
        file.setFileName(name);
        file.setFileType("FILE");
        file.setUserId(USER_ID);
        file.setUserName(USER_NAME);
        file.setIsFile("Y");
        file.setIsExist(isExist);
        file.setHasChild("N");
        file.setDel("N");
        return file;
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
}
