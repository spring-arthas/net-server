package com.alibaba.server.nio.media;

import com.alibaba.server.nio.media.model.ByteRange;
import org.apache.commons.lang.StringUtils;

public final class RangeHeaderParser {
    private static final String PREFIX = "bytes=";

    private RangeHeaderParser() {
    }

    public static ByteRange parse(String header, long totalSize) {
        if (totalSize < 0) {
            return ByteRange.invalid();
        }
        if (StringUtils.isBlank(header)) {
            return ByteRange.full(totalSize);
        }

        String value = header.trim().toLowerCase();
        if (value.startsWith("range:")) {
            value = value.substring("range:".length()).trim();
        }
        if (!value.startsWith(PREFIX)) {
            return ByteRange.full(totalSize);
        }

        String spec = value.substring(PREFIX.length()).trim();
        if (spec.contains(",")) {
            return ByteRange.invalid();
        }

        String[] parts = spec.split("-", -1);
        if (parts.length != 2) {
            return ByteRange.invalid();
        }

        if (totalSize == 0) {
            return ByteRange.invalid();
        }

        String startPart = parts[0].trim();
        String endPart = parts[1].trim();
        if (StringUtils.isBlank(startPart) && StringUtils.isBlank(endPart)) {
            return ByteRange.invalid();
        }

        try {
            if (StringUtils.isBlank(startPart)) {
                long suffixLength = Long.parseLong(endPart);
                if (suffixLength <= 0) {
                    return ByteRange.invalid();
                }
                long length = Math.min(suffixLength, totalSize);
                return ByteRange.partial(totalSize - length, totalSize - 1);
            }

            long start = Long.parseLong(startPart);
            if (start < 0 || start >= totalSize) {
                return ByteRange.invalid();
            }

            long end;
            if (StringUtils.isBlank(endPart)) {
                end = totalSize - 1;
            } else {
                end = Long.parseLong(endPart);
                if (end < start) {
                    return ByteRange.invalid();
                }
                end = Math.min(end, totalSize - 1);
            }
            return ByteRange.partial(start, end);
        } catch (NumberFormatException e) {
            return ByteRange.invalid();
        }
    }
}
