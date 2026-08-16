package com.alibaba.server.nio.service.file.security;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SessionTokenServiceTest {

    @Test(expected = IllegalArgumentException.class)
    public void blankSecretIsRejected() {
        new SessionTokenService("  ", 60L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void publicPlaceholderSecretIsRejected() {
        new SessionTokenService("change-me-session-secret", 60L);
    }

    @Test
    public void generatedTokenValidatesAgainstCurrentCredential() {
        AtomicLong now = new AtomicLong(1_000L);
        SessionTokenService service = new SessionTokenService("session-secret", 60L, now::get);

        String token = service.generateToken(7L, "alice", "password-v1");
        SessionTokenService.ValidationResult result = service.validateToken(
                token,
                userId -> userId == 7L ? "password-v1" : null);

        assertTrue(result.isValid());
        assertEquals(Long.valueOf(7L), result.getUserId());
        assertEquals("alice", result.getUserName());
        assertEquals(61_000L, result.getExpiresAt());
    }

    @Test
    public void tamperedTokenIsRejectedBeforeCredentialLookup() {
        SessionTokenService service = new SessionTokenService("session-secret", 60L, () -> 1_000L);
        String token = service.generateToken(7L, "alice", "password-v1");
        AtomicLong lookupCount = new AtomicLong();

        String replacement = token.endsWith("0") ? "1" : "0";
        SessionTokenService.ValidationResult result = service.validateToken(
                token.substring(0, token.length() - 1) + replacement,
                userId -> {
                    lookupCount.incrementAndGet();
                    return "password-v1";
                });

        assertFalse(result.isValid());
        assertEquals("session token signature invalid", result.getMessage());
        assertEquals(0L, lookupCount.get());
    }

    @Test
    public void expiredTokenIsRejected() {
        AtomicLong now = new AtomicLong(1_000L);
        SessionTokenService service = new SessionTokenService("session-secret", 60L, now::get);
        String token = service.generateToken(7L, "alice", "password-v1");

        now.set(61_001L);
        SessionTokenService.ValidationResult result = service.validateToken(token, userId -> "password-v1");

        assertFalse(result.isValid());
        assertEquals("session token expired", result.getMessage());
    }

    @Test
    public void passwordChangeInvalidatesExistingToken() {
        SessionTokenService service = new SessionTokenService("session-secret", 60L, () -> 1_000L);
        String token = service.generateToken(7L, "alice", "password-v1");

        SessionTokenService.ValidationResult result = service.validateToken(token, userId -> "password-v2");

        assertFalse(result.isValid());
        assertEquals("session credential changed", result.getMessage());
    }
}
