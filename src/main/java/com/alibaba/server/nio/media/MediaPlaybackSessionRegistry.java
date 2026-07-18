package com.alibaba.server.nio.media;

import org.apache.commons.lang.StringUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

public final class MediaPlaybackSessionRegistry {
    private static final long DEFAULT_SESSION_TTL_MILLIS = 2L * 60L * 60L * 1000L;
    private static final long DEFAULT_SEEK_WINDOW_MILLIS = 5_000L;
    private static final int MAX_SESSION_ID_LENGTH = 128;

    private final Map<String, PlaybackSession> sessions = new ConcurrentHashMap<>();
    private final long sessionTtlMillis;
    private final long seekWindowMillis;
    private final LongSupplier clock;

    public MediaPlaybackSessionRegistry() {
        this(DEFAULT_SESSION_TTL_MILLIS, DEFAULT_SEEK_WINDOW_MILLIS, System::currentTimeMillis);
    }

    MediaPlaybackSessionRegistry(long sessionTtlMillis, long seekWindowMillis, LongSupplier clock) {
        this.sessionTtlMillis = sessionTtlMillis;
        this.seekWindowMillis = seekWindowMillis;
        this.clock = clock;
    }

    public boolean register(String sessionId, Long fileId, String userName) {
        if (!isValidIdentity(sessionId, fileId)) {
            return false;
        }
        long now = clock.getAsLong();
        cleanupExpired(now);
        sessions.put(sessionId, new PlaybackSession(fileId, safe(userName), now));
        return true;
    }

    public boolean touch(String sessionId, Long fileId, String userName) {
        PlaybackSession session = matchingSession(sessionId, fileId, userName);
        if (session == null) {
            return false;
        }
        session.lastActivityAt = clock.getAsLong();
        return true;
    }

    public boolean markSeek(String sessionId, Long fileId, String userName) {
        PlaybackSession session = matchingSession(sessionId, fileId, userName);
        if (session == null) {
            return false;
        }
        long now = clock.getAsLong();
        session.lastSeekAt = now;
        session.lastActivityAt = now;
        return true;
    }

    public boolean wasRecentlySeeking(String sessionId, Long fileId, String userName) {
        PlaybackSession session = matchingSession(sessionId, fileId, userName);
        if (session == null || session.lastSeekAt < 0L) {
            return false;
        }
        long elapsed = clock.getAsLong() - session.lastSeekAt;
        return elapsed >= 0L && elapsed <= seekWindowMillis;
    }

    private PlaybackSession matchingSession(String sessionId, Long fileId, String userName) {
        if (!isValidIdentity(sessionId, fileId)) {
            return null;
        }
        PlaybackSession session = sessions.get(sessionId);
        if (session == null) {
            return null;
        }
        long now = clock.getAsLong();
        if (now - session.lastActivityAt > sessionTtlMillis) {
            sessions.remove(sessionId, session);
            return null;
        }
        if (!fileId.equals(session.fileId) || !safe(userName).equals(session.userName)) {
            return null;
        }
        return session;
    }

    private void cleanupExpired(long now) {
        for (Map.Entry<String, PlaybackSession> entry : sessions.entrySet()) {
            PlaybackSession session = entry.getValue();
            if (now - session.lastActivityAt > sessionTtlMillis) {
                sessions.remove(entry.getKey(), session);
            }
        }
    }

    private boolean isValidIdentity(String sessionId, Long fileId) {
        return fileId != null
                && StringUtils.isNotBlank(sessionId)
                && sessionId.length() <= MAX_SESSION_ID_LENGTH;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private static final class PlaybackSession {
        private final Long fileId;
        private final String userName;
        private volatile long lastActivityAt;
        private volatile long lastSeekAt = -1L;

        private PlaybackSession(Long fileId, String userName, long now) {
            this.fileId = fileId;
            this.userName = userName;
            this.lastActivityAt = now;
        }
    }
}
