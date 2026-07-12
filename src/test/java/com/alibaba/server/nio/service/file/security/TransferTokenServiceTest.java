package com.alibaba.server.nio.service.file.security;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TransferTokenServiceTest {

    @Test
    public void validatesIssuedTokenAndIdentity() {
        TransferTokenService service = new TransferTokenService("test-secret", 3600L);

        String token = service.generateToken(1001L, "18806504525");
        TransferTokenService.ValidationResult result = service.validateToken(token);

        assertTrue(result.isValid());
        assertEquals(Long.valueOf(1001L), result.getUserId());
        assertEquals("18806504525", result.getUserName());
    }

    @Test
    public void rejectsTamperedToken() {
        TransferTokenService service = new TransferTokenService("test-secret", 3600L);
        String token = service.generateToken(1001L, "18806504525");

        TransferTokenService.ValidationResult result = service.validateToken(token + "tampered");

        assertFalse(result.isValid());
    }
}
