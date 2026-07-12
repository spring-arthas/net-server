package com.alibaba.server.nio.media.model;

public class ByteRange {
    private final long start;
    private final long end;
    private final boolean partial;
    private final boolean invalid;

    private ByteRange(long start, long end, boolean partial, boolean invalid) {
        this.start = start;
        this.end = end;
        this.partial = partial;
        this.invalid = invalid;
    }

    public static ByteRange full(long totalSize) {
        if (totalSize <= 0) {
            return new ByteRange(0, -1, false, false);
        }
        return new ByteRange(0, totalSize - 1, false, false);
    }

    public static ByteRange partial(long start, long end) {
        return new ByteRange(start, end, true, false);
    }

    public static ByteRange invalid() {
        return new ByteRange(0, -1, false, true);
    }

    public long getStart() {
        return start;
    }

    public long getEnd() {
        return end;
    }

    public boolean isPartial() {
        return partial;
    }

    public boolean isInvalid() {
        return invalid;
    }

    public long length() {
        if (invalid || end < start) {
            return 0;
        }
        return end - start + 1;
    }
}
