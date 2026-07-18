package com.alibaba.server.nio.service.file.handler;

/**
 * Pull-Range 窗口校验器。
 */
public final class RangePullWindowValidator {

    private RangePullWindowValidator() {
    }

    /**
     * 验证文件拉流范围的有效性
     * @param fileSize 原始文件大小
     * @param startOffset 拉取的起始位置
     * @param length 拉取的长度
     * */
    public static ValidationResult validate(long fileSize, long startOffset, long length) {
        if (startOffset < 0 || length <= 0) {
            return ValidationResult.error(41601, "range out of bounds");
        }
        if (fileSize <= 0 || startOffset >= fileSize) {
            return ValidationResult.error(41601, "range out of bounds");
        }

        long maxLength = fileSize - startOffset;
        long actualLength = Math.min(length, maxLength);
        if (actualLength <= 0) {
            return ValidationResult.error(41601, "range out of bounds");
        }
        return ValidationResult.ok(actualLength);
    }

    public static final class ValidationResult {
        private final boolean valid;
        private final int code;
        private final String message;
        private final long actualLength;

        private ValidationResult(boolean valid, int code, String message, long actualLength) {
            this.valid = valid;
            this.code = code;
            this.message = message;
            this.actualLength = actualLength;
        }

        public static ValidationResult ok(long actualLength) {
            return new ValidationResult(true, 0, null, actualLength);
        }

        public static ValidationResult error(int code, String message) {
            return new ValidationResult(false, code, message, 0);
        }

        public boolean isValid() {
            return valid;
        }

        public int getCode() {
            return code;
        }

        public String getMessage() {
            return message;
        }

        public long getActualLength() {
            return actualLength;
        }
    }
}
