package com.alibaba.server.nio.service.file.security;

import org.apache.commons.lang.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongSupplier;

public class SessionTokenService {
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final long DEFAULT_EXPIRE_SECONDS = 604800L;

    private final String secret;
    private final long expireSeconds;
    private final LongSupplier clock;

    public SessionTokenService(String secret, long expireSeconds) {
        this(secret, expireSeconds, System::currentTimeMillis);
    }

    SessionTokenService(String secret, long expireSeconds, LongSupplier clock) {
        if (StringUtils.isBlank(secret) || "change-me-session-secret".equalsIgnoreCase(secret.trim())) {
            throw new IllegalArgumentException("a non-default session token secret is required");
        }
        this.secret = secret.trim();
        this.expireSeconds = expireSeconds > 0 ? expireSeconds : DEFAULT_EXPIRE_SECONDS;
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    public String generateToken(Long userId, String userName, String credential) {
        if (userId == null || userId <= 0 || StringUtils.isBlank(userName) || credential == null) {
            throw new IllegalArgumentException("invalid session token identity");
        }
        long expiresAt = clock.getAsLong() + expireSeconds * 1000L;
        String encodedUserName = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(userName.getBytes(StandardCharsets.UTF_8));
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String credentialHmac = credentialHmac(userId, credential);
        String payload = userId + ":" + encodedUserName + ":" + expiresAt + ":" + nonce + ":" + credentialHmac;
        return payload + ":" + sign(payload);
    }

    public ValidationResult validateToken(String token, Function<Long, String> credentialLookup) {
        if (StringUtils.isBlank(token)) {
            return ValidationResult.invalid("session token is blank");
        }
        String[] parts = token.split(":", -1);
        if (parts.length != 6) {
            return ValidationResult.invalid("session token format invalid");
        }
        try {
            Long userId = Long.valueOf(parts[0]);
            long expiresAt = Long.parseLong(parts[2]);
            String payload = parts[0] + ":" + parts[1] + ":" + parts[2] + ":" + parts[3] + ":" + parts[4];
            if (!constantTimeEquals(parts[5], sign(payload))) {
                return ValidationResult.invalid("session token signature invalid");
            }
            if (clock.getAsLong() > expiresAt) {
                return ValidationResult.invalid("session token expired");
            }
            String credential = credentialLookup == null ? null : credentialLookup.apply(userId);
            if (credential == null || !constantTimeEquals(parts[4], credentialHmac(userId, credential))) {
                return ValidationResult.invalid("session credential changed");
            }
            String userName = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            return ValidationResult.valid(userId, userName, expiresAt);
        } catch (Exception e) {
            return ValidationResult.invalid("session token parse failed");
        }
    }

    private String credentialHmac(Long userId, String credential) {
        return sign("credential:" + userId + ":" + credential);
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return toHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Can not sign session token", e);
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
