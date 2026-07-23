package com.alibaba.server.nio.repository.file.service.impl;

import com.alibaba.server.nio.repository.file.mapper.FileRepository;
import com.alibaba.server.nio.repository.file.repository.dataobject.FileDo;
import com.alibaba.server.nio.repository.file.service.dto.FileTaskDto;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class FileServiceImplAssignedIdTest {

    @Test
    public void createByTaskKeepsAssignedSnowflakeIdWhenMapperSelectKeyOverwritesEntityId() throws Exception {
        FileServiceImpl service = new FileServiceImpl();
        AtomicLong insertedId = new AtomicLong();
        FileRepository repository = (FileRepository) Proxy.newProxyInstance(
                FileRepository.class.getClassLoader(),
                new Class<?>[] { FileRepository.class },
                (proxy, method, args) -> {
                    if ("insertSelective".equals(method.getName())) {
                        FileDo file = (FileDo) args[0];
                        insertedId.set(file.getId());
                        file.setId(455L);
                    } else if ("get".equals(method.getName())) {
                        FileDo persisted = new FileDo();
                        persisted.setId((Long) args[0]);
                        return persisted;
                    }
                    return null;
                });
        Field repositoryField = FileServiceImpl.class.getDeclaredField("fileRepository");
        repositoryField.setAccessible(true);
        repositoryField.set(service, repository);

        FileTaskDto task = new FileTaskDto();
        task.setParentId(100L);
        task.setFileName("attachment.zip");
        task.setFilePath("/tmp/attachment.zip");
        task.setFileType("zip");
        task.setFileSize(1024L);
        task.setUserId(5);
        task.setUserName("tester");

        FileDo created = service.createByTask(task);

        assertNotEquals(455L, insertedId.get());
        assertEquals(insertedId.get(), created.getId().longValue());
    }
}
