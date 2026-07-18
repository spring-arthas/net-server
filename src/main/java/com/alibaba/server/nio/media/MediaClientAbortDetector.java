package com.alibaba.server.nio.media;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;

final class MediaClientAbortDetector {
    private static final String[] CLIENT_ABORT_MARKERS = {
            "broken pipe",
            "connection reset",
            "stream closed",
            "socket closed",
            "insufficient bytes written to stream"
    };

    private MediaClientAbortDetector() {
    }

    static boolean isClientAbort(Throwable error) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        return isClientAbort(error, visited);
    }

    private static boolean isClientAbort(Throwable error, Set<Throwable> visited) {
        if (error == null || !visited.add(error)) {
            return false;
        }
        String message = error.getMessage();
        if (message != null) {
            String normalized = message.toLowerCase(Locale.ROOT);
            for (String marker : CLIENT_ABORT_MARKERS) {
                if (normalized.contains(marker)) {
                    return true;
                }
            }
        }
        if (isClientAbort(error.getCause(), visited)) {
            return true;
        }
        for (Throwable suppressed : error.getSuppressed()) {
            if (isClientAbort(suppressed, visited)) {
                return true;
            }
        }
        return false;
    }
}
