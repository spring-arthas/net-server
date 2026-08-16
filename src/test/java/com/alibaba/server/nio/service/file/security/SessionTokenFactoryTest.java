package com.alibaba.server.nio.service.file.security;

import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SessionTokenFactoryTest {

    @Test
    public void missingDeploymentSecretUsesPersistentLocalSecret() {
        AtomicInteger localSecretLoads = new AtomicInteger();
        SessionTokenService service = SessionTokenFactory.create(
                Collections.emptyMap(),
                name -> null,
                () -> {
                    localSecretLoads.incrementAndGet();
                    return "generated-local-secret-with-enough-entropy";
                });

        String token = service.generateToken(7L, "alice", "password-v1");

        assertEquals(1, localSecretLoads.get());
        assertTrue(service.validateToken(token, userId -> "password-v1").isValid());
    }

    @Test
    public void environmentSecretOverridesRepositoryConfiguration() {
        Map<String, Object> config = new HashMap<>();
        config.put("USER.SESSION.TOKEN.SECRET", "change-me-session-secret");
        AtomicInteger localSecretLoads = new AtomicInteger();

        SessionTokenService service = SessionTokenFactory.create(
                config,
                name -> "USER_SESSION_TOKEN_SECRET".equals(name) ? "deployment-secret-with-enough-entropy" : null,
                () -> {
                    localSecretLoads.incrementAndGet();
                    return "unused-local-secret";
                });
        String token = service.generateToken(7L, "alice", "password-v1");

        assertEquals(0, localSecretLoads.get());
        assertTrue(service.validateToken(token, userId -> "password-v1").isValid());
    }
}
