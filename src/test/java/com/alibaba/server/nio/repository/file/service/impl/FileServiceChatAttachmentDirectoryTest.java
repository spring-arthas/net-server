package com.alibaba.server.nio.repository.file.service.impl;

import com.alibaba.server.common.BasicConstant;
import com.alibaba.server.nio.core.server.BasicServer;
import com.alibaba.server.nio.repository.file.mapper.FileRepository;
import com.alibaba.server.nio.repository.file.repository.dataobject.FileDo;
import com.alibaba.server.nio.repository.file.repository.param.FileDalQueryParam;
import com.alibaba.server.nio.repository.file.service.dto.FileDto;
import com.alibaba.server.nio.repository.user.service.dto.UserDTO;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class FileServiceChatAttachmentDirectoryTest {

    private static final Integer USER_ID = 7;
    private static final String USER_NAME = "18806504525";

    private Path storageRoot;
    private Object previousStorageRoot;
    private List<FileDo> records;
    private FileServiceImpl fileService;

    @Before
    public void setUp() throws Exception {
        storageRoot = Files.createTempDirectory("chat-attachment-directory-test");
        previousStorageRoot = BasicServer.getMap().put(
                BasicConstant.NIO_FILE_BASE_PATH_LINUX_MAC, storageRoot.toString());
        records = new ArrayList<>();
        records.add(directory(1L, -1L, USER_NAME));
        fileService = new FileServiceImpl();

        FileRepository repository = (FileRepository) Proxy.newProxyInstance(
                FileRepository.class.getClassLoader(),
                new Class<?>[] { FileRepository.class },
                (proxy, method, args) -> {
                    String methodName = method.getName();
                    if ("get".equals(methodName)) {
                        Long id = (Long) args[0];
                        return records.stream().filter(item -> id.equals(item.getId())).findFirst().orElse(null);
                    }
                    if ("getAssignFiles".equals(methodName)) {
                        FileDalQueryParam param = (FileDalQueryParam) args[0];
                        List<FileDo> matches = new ArrayList<>();
                        for (FileDo item : records) {
                            if (param.getParentId() != null && !param.getParentId().equals(item.getParentId())) {
                                continue;
                            }
                            if (param.getFileName() != null && !param.getFileName().equals(item.getFileName())) {
                                continue;
                            }
                            if (param.getUserId() != null && !param.getUserId().equals(item.getUserId())) {
                                continue;
                            }
                            if (param.getIsFile() != null && !param.getIsFile().equals(item.getIsFile())) {
                                continue;
                            }
                            if (param.getDel() != null && !param.getDel().equals(item.getDel())) {
                                continue;
                            }
                            matches.add(item);
                        }
                        return matches;
                    }
                    if ("insertSelective".equals(methodName)) {
                        records.add((FileDo) args[0]);
                        return null;
                    }
                    if ("updateSelective".equals(methodName)) {
                        FileDo update = (FileDo) args[0];
                        FileDo current = records.stream()
                                .filter(item -> update.getId().equals(item.getId()))
                                .findFirst().orElse(null);
                        if (current != null) {
                            if (update.getFilePath() != null) {
                                current.setFilePath(update.getFilePath());
                            }
                            if (update.getHasChild() != null) {
                                current.setHasChild(update.getHasChild());
                            }
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
    public void createsAndReusesPositiveChatAttachmentDirectory() throws Exception {
        FileDto first = fileService.ensureChatAttachmentDirectory(USER_ID, USER_NAME);
        FileDto second = fileService.ensureChatAttachmentDirectory(USER_ID, USER_NAME);

        assertNotNull(first.getId());
        assertTrue(first.getId() > 0L);
        assertEquals(first.getId(), second.getId());
        assertEquals(1L, first.getParentId().longValue());
        assertEquals(".chat-attachments", first.getFileName());
        assertEquals(USER_ID, first.getUserId());
        assertEquals(USER_NAME, first.getUserName());
        assertTrue(Files.isDirectory(storageRoot.resolve(USER_NAME).resolve(".chat-attachments")));
        assertEquals(2, records.size());
    }

    @Test
    public void omitsChatAttachmentDirectoryFromUserTree() throws Exception {
        fileService.ensureChatAttachmentDirectory(USER_ID, USER_NAME);
        UserDTO user = new UserDTO();
        user.setId(USER_ID.longValue());
        user.setUserName(USER_NAME);

        FileDto root = fileService.handleUserTwoLevelDirectory(user);

        assertEquals(USER_NAME, root.getFileName());
        assertTrue(root.getChildFileList().isEmpty());
    }

    private FileDo directory(Long id, Long parentId, String name) {
        FileDo directory = new FileDo();
        directory.setId(id);
        directory.setParentId(parentId);
        directory.setFileName(name);
        directory.setFilePath(storageRoot.resolve(name).toString());
        directory.setFileType("NOT_FILE");
        directory.setUserId(USER_ID);
        directory.setUserName(USER_NAME);
        directory.setIsFile("N");
        directory.setIsExist("Y");
        directory.setHasChild("N");
        directory.setDel("N");
        return directory;
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
