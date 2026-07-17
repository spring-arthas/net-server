package com.alibaba.server.nio.service.file.security;

import org.apache.commons.lang.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;

public class TransferTokenService {
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final String secret;
    private final long expireSeconds;

    public TransferTokenService(String secret, long expireSeconds) {
        this.secret = StringUtils.defaultIfBlank(secret, "change-me");
        this.expireSeconds = expireSeconds > 0 ? expireSeconds : 86400L;
    }

    public String generateToken(Long userId, String userName) {
        if (userId == null || userId <= 0 || StringUtils.isBlank(userName)) {
            throw new IllegalArgumentException("invalid transfer token identity");
        }
        long expiresAt = System.currentTimeMillis() + expireSeconds * 1000L;
        String encodedUserName = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(userName.getBytes(StandardCharsets.UTF_8));
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String payload = userId + ":" + encodedUserName + ":" + expiresAt + ":" + nonce;
        return payload + ":" + sign(payload);
    }

    public ValidationResult validateToken(String token) {
        if (StringUtils.isBlank(token)) {
            return ValidationResult.invalid("transfer token is blank");
        }
        String[] parts = token.split(":", -1);
        if (parts.length != 5) {
            return ValidationResult.invalid("transfer token format invalid");
        }
        try {
            Long userId = Long.valueOf(parts[0]);
            String userName = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            long expiresAt = Long.parseLong(parts[2]);
            String payload = parts[0] + ":" + parts[1] + ":" + parts[2] + ":" + parts[3];
            if (!constantTimeEquals(parts[4], sign(payload))) {
                return ValidationResult.invalid("transfer token signature invalid");
            }
            if (System.currentTimeMillis() > expiresAt) {
                return ValidationResult.invalid("transfer token expired");
            }
            return ValidationResult.valid(userId, userName, expiresAt);
        } catch (Exception e) {
            return ValidationResult.invalid("transfer token parse failed");
        }
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return toHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Can not sign transfer token", e);
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        return left != null && right != null
                && MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format("%02x", value & 0xff));
        }
        return builder.toString();
    }

    public static class ValidationResult {
        private final boolean valid;
        private final Long userId;
        private final String userName;
        private final long expiresAt;
        private final String message;

        private ValidationResult(boolean valid, Long userId, String userName, long expiresAt, String message) {
            this.valid = valid;
            this.userId = userId;
            this.userName = userName;
            this.expiresAt = expiresAt;
            this.message = message;
        }

        public static ValidationResult valid(Long userId, String userName, long expiresAt) {
            return new ValidationResult(true, userId, userName, expiresAt, null);
        }

        public static ValidationResult invalid(String message) {
            return new ValidationResult(false, null, null, 0L, message);
        }

        public boolean isValid() { return valid; }
        public Long getUserId() { return userId; }
        public String getUserName() { return userName; }
        public long getExpiresAt() { return expiresAt; }
        public String getMessage() { return message; }
    }
}
