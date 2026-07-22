package com.alibaba.server.nio.util;

import java.nio.Buffer;

/**
 * Keeps NIO buffer state transitions binary-compatible with Java 8 even when
 * sources are accidentally compiled by a newer JDK.
 */
public final class NioBufferCompat {

    private NioBufferCompat() {
    }

    public static void clear(Buffer buffer) {
        buffer.clear();
    }

    public static void flip(Buffer buffer) {
        buffer.flip();
    }

    public static void limit(Buffer buffer, int newLimit) {
        buffer.limit(newLimit);
    }

    public static void position(Buffer buffer, int newPosition) {
        buffer.position(newPosition);
    }
}
