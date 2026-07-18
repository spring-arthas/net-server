package com.alibaba.server.nio.service.file.handler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 上传文件最终完整性校验器。
 */
public final class UploadIntegrityVerifier {

    private static final int HASH_BUFFER_SIZE = 1024 * 1024;

    private UploadIntegrityVerifier() {
    }

    /**
     * 校验磁盘文件大小和 MD5。
     *
     * @param filePath    磁盘文件路径
     * @param expectedSize 客户端声明大小
     * @param expectedMd5  客户端声明 MD5
     * @return 校验结果
     * @throws IOException 文件读取失败
     */
    public static VerificationResult verify(Path filePath, long expectedSize, String expectedMd5) throws IOException {
        if (filePath == null || !Files.isRegularFile(filePath)) {
            return VerificationResult.invalid("上传文件不存在");
        }
        long actualSize = Files.size(filePath);
        if (actualSize != expectedSize) {
            return VerificationResult.invalid(
                    "上传文件大小校验失败: expected=" + expectedSize + ", actual=" + actualSize);
        }
        if (expectedMd5 == null || expectedMd5.trim().isEmpty()) {
            return VerificationResult.invalid("上传文件缺少 MD5，拒绝完成");
        }
        String actualMd5 = calculateMd5(filePath);
        if (!actualMd5.equalsIgnoreCase(expectedMd5.trim())) {
            return VerificationResult.invalid(
                    "上传文件 MD5 校验失败: expected=" + expectedMd5 + ", actual=" + actualMd5);
        }
        return VerificationResult.valid(actualMd5);
    }

    private static String calculateMd5(Path filePath) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 不支持 MD5", e);
        }
        byte[] buffer = new byte[HASH_BUFFER_SIZE];
        try (InputStream inputStream = Files.newInputStream(filePath)) {
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder builder = new StringBuilder(32);
        for (byte value : digest.digest()) {
            builder.append(String.format("%02x", value & 0xff));
        }
        return builder.toString();
    }

    public static final class VerificationResult {
        private final boolean valid;
        private final String actualMd5;
        private final String message;

        private VerificationResult(boolean valid, String actualMd5, String message) {
            this.valid = valid;
            this.actualMd5 = actualMd5;
            this.message = message;
        }

        private static VerificationResult valid(String actualMd5) {
            return new VerificationResult(true, actualMd5, null);
        }

        private static VerificationResult invalid(String message) {
            return new VerificationResult(false, null, message);
        }

        public boolean isValid() {
            return valid;
        }

        public String getActualMd5() {
            return actualMd5;
        }

        public String getMessage() {
            return message;
        }
    }
}
