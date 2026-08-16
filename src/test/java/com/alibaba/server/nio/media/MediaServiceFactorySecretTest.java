package com.alibaba.server.nio.media;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MediaServiceFactorySecretTest {

    @Test
    public void environmentSecretOverridesConfiguredAndLocalSecrets() {
        Map<String, Object> config = new HashMap<>();
        config.put("MEDIA.STREAM.TOKEN.SECRET", "configured-secret");
        AtomicInteger localLoads = new AtomicInteger();

        MediaTokenService service = MediaServiceFactory.createTokenService(
                config,
                name -> "MEDIA_STREAM_TOKEN_SECRET".equals(name) ? "environment-secret" : null,
                () -> {
                    localLoads.incrementAndGet();
                    return "local-secret";
                });

        String token = service.generateToken(9L, "alice");
        assertTrue(new MediaTokenService("environment-secret", 300L)
                .validateToken(token, 9L, "alice").isValid());
        assertEquals(0, localLoads.get());
    }

    @Test
    public void blankConfigurationUsesLocalSecretSupplier() {
        Map<String, Object> config = new HashMap<>();
        config.put("MEDIA.STREAM.TOKEN.SECRET", " ");
        AtomicInteger localLoads = new AtomicInteger();

        MediaTokenService service = MediaServiceFactory.createTokenService(
                config,
                name -> null,
                () -> {
                    localLoads.incrementAndGet();
                    return "local-secret";
                });

        String token = service.generateToken(9L, "alice");
        assertTrue(new MediaTokenService("local-secret", 300L)
                .validateToken(token, 9L, "alice").isValid());
        assertEquals(1, localLoads.get());
    }
}
