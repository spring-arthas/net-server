package com.alibaba.server.nio.media;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MediaTokenServiceTest {

    @Test(expected = IllegalArgumentException.class)
    public void rejectsPublicDefaultSecret() {
        new MediaTokenService("change-me", 300);
    }

    @Test
    public void generatedTokenValidatesForSameFileAndUser() {
        MediaTokenService service = new MediaTokenService("test-secret", 300);

        String token = service.generateToken(123L, "spring");

        MediaTokenService.ValidationResult result = service.validateToken(token, 123L, "spring");

        assertTrue(result.isValid());
        assertNull(result.getUserId());
    }

    @Test
    public void newTokenCarriesUserIdForDynamicMediaAuthorization() {
        MediaTokenService service = new MediaTokenService("test-secret", 300);

        String token = service.generateToken(123L, 7L, "spring");
        MediaTokenService.ValidationResult result = service.validateToken(token, 123L, "spring");

        assertTrue(result.isValid());
        assertEquals(Long.valueOf(7L), result.getUserId());
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
