package com.alibaba.server.nio.service.file.security;

import com.alibaba.server.common.BasicConstant;
import com.alibaba.server.nio.model.file.FileUploadFrame;
import org.junit.Test;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class UserSessionProtocolTest {

    @Test
    public void persistentSessionFrameKeepsAndroidProtocolCode() {
        FileUploadFrame.FrameType type = FileUploadFrame.FrameType.fromCode(0x46);

        assertNotNull(type);
        assertEquals(FileUploadFrame.FrameType.USER_SESSION_RESUME_REQ, type);
    }

    @Test
    public void persistentSessionUsesDedicatedOneYearConfiguration() throws Exception {
        Properties properties = new Properties();
        try (InputStream input = new FileInputStream("src/main/resources/server.properties")) {
            properties.load(input);
        }

        assertTrue(properties.containsKey(BasicConstant.USER_SESSION_TOKEN_SECRET));
        assertEquals(
                String.valueOf(365L * 24L * 60L * 60L),
                properties.getProperty(BasicConstant.USER_SESSION_TOKEN_EXPIRE_SECONDS).trim()
        );
    }
}
