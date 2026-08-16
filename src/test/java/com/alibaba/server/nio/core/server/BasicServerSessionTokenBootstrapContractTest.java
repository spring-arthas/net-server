package com.alibaba.server.nio.core.server;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

public class BasicServerSessionTokenBootstrapContractTest {

    @Test
    public void sessionTokenServiceIsInitializedImmediatelyAfterConfigurationLoad() throws Exception {
        Path sourcePath = Paths.get(
                "src/main/java/com/alibaba/server/nio/core/server/BasicServer.java");
        String source = new String(Files.readAllBytes(sourcePath), StandardCharsets.UTF_8);

        int loadConfigIndex = source.indexOf("loadConfigProperties();");
        int initializeSessionTokenIndex = source.indexOf("SessionTokenFactory.getInstance();");
        int setEnumsIndex = source.indexOf("setEnumsType();");

        assertTrue("BasicServer must load configuration before session-token initialization",
                loadConfigIndex >= 0 && initializeSessionTokenIndex > loadConfigIndex);
        assertTrue("Session-token configuration must fail before core server initialization",
                setEnumsIndex >= 0 && initializeSessionTokenIndex < setEnumsIndex);
    }
}
