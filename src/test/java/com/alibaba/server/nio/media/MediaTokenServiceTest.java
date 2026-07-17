package com.alibaba.server.nio.media;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MediaTokenServiceTest {

    @Test
    public void generatedTokenValidatesForSameFileAndUser() {
        MediaTokenService service = new MediaTokenService("test-secret", 300);

        String token = service.generateToken(123L, "spring");

        assertTrue(service.validateToken(token, 123L, "spring").isValid());
    }

    @Test
    public void tokenDoesNotValidateForDifferentUser() {
        MediaTokenService service = new MediaTokenService("test-secret", 300);

        String token = service.generateToken(123L, "spring");

        assertFalse(service.validateToken(token, 123L, "other").isValid());
    }

    @Test
    public void tokenDoesNotValidateAfterTampering() {
        MediaTokenService service = new MediaTokenService("test-secret", 300);

        String token = service.generateToken(123L, "spring");
        String tampered = token.replace("spring", "other");

        assertFalse(service.validateToken(tampered, 123L, "spring").isValid());
    }

    @Test
    public void expiredTokenIsRejected() {
        MediaTokenService service = new MediaTokenService("test-secret", -1);

        String token = service.generateToken(123L, "spring");

        assertFalse(service.validateToken(token, 123L, "spring").isValid());
    }
}
