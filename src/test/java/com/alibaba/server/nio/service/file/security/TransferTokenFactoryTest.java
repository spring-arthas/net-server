package com.alibaba.server.nio.service.file.security;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TransferTokenFactoryTest {

    @Test
    public void environmentSecretOverridesConfiguredAndLocalSecrets() {
        Map<String, Object> config = new HashMap<>();
        config.put("FILE.TRANSFER.TOKEN.SECRET", "configured-secret");
        AtomicInteger localLoads = new AtomicInteger();

        TransferTokenService service = TransferTokenFactory.create(
                config,
                name -> "FILE_TRANSFER_TOKEN_SECRET".equals(name) ? "environment-secret" : null,
                () -> {
                    localLoads.incrementAndGet();
                    return "local-secret";
                });

        String token = service.generateToken(9L, "alice");
        assertTrue(new TransferTokenService("environment-secret", 86400L).validateToken(token).isValid());
        assertEquals(0, localLoads.get());
    }

    @Test
    public void blankConfigurationUsesLocalSecretSupplier() {
        Map<String, Object> config = new HashMap<>();
        config.put("FILE.TRANSFER.TOKEN.SECRET", " ");
        AtomicInteger localLoads = new AtomicInteger();

        TransferTokenService service = TransferTokenFactory.create(
                config,
                name -> null,
                () -> {
                    localLoads.incrementAndGet();
                    return "local-secret";
                });

        String token = service.generateToken(9L, "alice");
        assertTrue(new TransferTokenService("local-secret", 86400L).validateToken(token).isValid());
        assertEquals(1, localLoads.get());
    }
}
