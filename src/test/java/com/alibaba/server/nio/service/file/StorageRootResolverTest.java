package com.alibaba.server.nio.service.file;

import com.alibaba.server.common.BasicConstant;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class StorageRootResolverTest {

    @Test
    public void selectsWindowsStorageRootForWindowsServer() {
        Map<String, Object> config = configuration("Windows 11");

        assertEquals("F:\\storage\\upload\\file", StorageRootResolver.resolve(config));
    }

    @Test
    public void selectsMacStorageRootForMacServer() {
        Map<String, Object> config = configuration("Mac OS X");

        assertEquals("/Users/test/Documents/storages", StorageRootResolver.resolve(config));
    }

    private Map<String, Object> configuration(String osName) {
        Map<String, Object> config = new HashMap<>();
        config.put(BasicConstant.OS_NAME, osName);
        config.put(BasicConstant.NIO_FILE_BASE_PATH_WINDOWS, "F:\\storage\\upload\\file");
        config.put(BasicConstant.NIO_FILE_BASE_PATH_LINUX_MAC, "/Users/test/Documents/storages");
        return config;
    }
}
