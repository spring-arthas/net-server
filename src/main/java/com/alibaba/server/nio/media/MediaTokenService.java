package com.alibaba.server.nio.media;

import org.apache.commons.lang.StringUtils;
import com.alibaba.server.nio.service.file.security.TokenSecretResolver;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;

public class MediaTokenService {
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final String secret;
    private final long expireSeconds;

    public MediaTokenService(String secret, long expireSeconds) {
        if (!TokenSecretResolver.isUsable(secret)) {
            throw new IllegalArgumentException("必须配置非默认媒体令牌密钥");
        }
        this.secret = secret.trim();
        this.expireSeconds = expireSeconds;
    }

    public String generateToken(Long fileId, String userName) {
        long expiresAt = System.currentTimeMillis() + expireSeconds * 1000L;
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String payload = fileId + ":" + safe(userName) + ":" + expiresAt + ":" + nonce;
        return payload + ":" + sign(payload);
    }

    /** 生成携带登录用户 ID 的新版媒体令牌；旧五段令牌仍由 validateToken 兼容。 */
    public String generateToken(Long fileId, Long userId, String userName) {
        if (fileId == null || userId == null || userId <= 0L) {
            throw new IllegalArgumentException("invalid media token identity");
        }
        long expiresAt = System.currentTimeMillis() + expireSeconds * 1000L;
        String encodedUserName = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(safe(userName).getBytes(StandardCharsets.UTF_8));
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String payload = fileId + ":" + userId + ":" + encodedUserName + ":" + expiresAt + ":" + nonce;
        return payload + ":" + sign(payload);
    }

    public ValidationResult validateToken(String token) {
        if (StringUtils.isBlank(token)) {
            return ValidationResult.invalid("token is blank");
        }
        String[] parts = token.split(":", -1);
        if (parts.length != 5 && parts.length != 6) {
            return ValidationResult.invalid("token format invalid");
        }

        try {
            Long fileId = Long.valueOf(parts[0]);
            Long userId = null;
            String userName;
            long expiresAt;
            String payload;
            String signature;
            if (parts.length == 6) {
                userId = Long.valueOf(parts[1]);
                userName = new String(Base64.getUrlDecoder().decode(parts[2]), StandardCharsets.UTF_8);
                expiresAt = Long.parseLong(parts[3]);
                payload = parts[0] + ":" + parts[1] + ":" + parts[2] + ":" + parts[3] + ":" + parts[4];
                signature = parts[5];
            } else {
                // [修改] 保留已签发的 Android/macOS 五段媒体令牌。
                userName = parts[1];
                expiresAt = Long.parseLong(parts[2]);
                payload = fileId + ":" + userName + ":" + expiresAt + ":" + parts[3];
                signature = parts[4];
            }
            if (!constantTimeEquals(signature, sign(payload))) {
                return ValidationResult.invalid("signature invalid");
            }
            if (System.currentTimeMillis() > expiresAt) {
                return ValidationResult.invalid("token expired");
            }
            return ValidationResult.valid(fileId, userId, userName, expiresAt);
        } catch (Exception e) {
            return ValidationResult.invalid("token parse failed");
        }
    }

    public ValidationResult validateToken(String token, Long expectedFileId, String expectedUserName) {
        ValidationResult result = validateToken(token);
        if (!result.isValid()) {
            return result;
        }
        if (!result.getFileId().equals(expectedFileId)) {
            return ValidationResult.invalid("fileId mismatch");
        }
        if (!safe(expectedUserName).equals(result.getUserName())) {
            return ValidationResult.invalid("userName mismatch");
        }
        return result;
    }

    public long getExpireSeconds() {
        return expireSeconds;
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return toHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Can not sign media token", e);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private static String toHex(byte[] data) {
        StringBuilder builder = new StringBuilder(data.length * 2);
        for (byte b : data) {
            builder.append(String.format("%02x", b & 0xff));
        }
        return builder.toString();
    }

    public static class ValidationResult {
        private final boolean valid;
        private final Long fileId;
        private final Long userId;
        private final String userName;
        private final long expiresAt;
        private final String message;

        private ValidationResult(boolean valid, Long fileId, Long userId, String userName, long expiresAt, String message) {
            this.valid = valid;
            this.fileId = fileId;
            this.userId = userId;
            this.userName = userName;
            this.expiresAt = expiresAt;
            this.message = message;
        }

        public static ValidationResult valid(Long fileId, String userName, long expiresAt) {
            return valid(fileId, null, userName, expiresAt);
        }

        public static ValidationResult valid(Long fileId, Long userId, String userName, long expiresAt) {
            return new ValidationResult(true, fileId, userId, userName, expiresAt, null);
        }

        public static ValidationResult invalid(String message) {
            return new ValidationResult(false, null, null, null, 0L, message);
        }

        public boolean isValid() {
            return valid;
        }

        public Long getFileId() {
            return fileId;
        }

        public Long getUserId() {
            return userId;
        }

        public String getUserName() {
            return userName;
        }

        public long getExpiresAt() {
            return expiresAt;
        }

        public String getMessage() {
            return message;
        }
    }
}
