package com.alibaba.server.config;

import org.junit.Test;

import java.io.InputStream;
import java.util.Properties;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TokenSecretConfigurationContractTest {

    @Test
    public void repositoryDoesNotShipPublicTokenSecrets() throws Exception {
        Properties properties = new Properties();
        try (InputStream input = getClass().getResourceAsStream("/server.properties")) {
            assertTrue(input != null);
            properties.load(input);
        }

        String mediaSecret = properties.getProperty("MEDIA.STREAM.TOKEN.SECRET", "").trim();
        String transferSecret = properties.getProperty("FILE.TRANSFER.TOKEN.SECRET", "").trim();
        assertTrue(mediaSecret.isEmpty());
        assertTrue(transferSecret.isEmpty());
        assertFalse(mediaSecret.toLowerCase().contains("change-me"));
        assertFalse(transferSecret.toLowerCase().contains("change-me"));
    }
}
