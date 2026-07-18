package com.alibaba.server.nio.media;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MediaPlaybackSessionRegistryTest {

    @Test
    public void marksSeekOnlyForMatchingPlaybackSession() {
        AtomicLong now = new AtomicLong(1_000L);
        MediaPlaybackSessionRegistry registry = new MediaPlaybackSessionRegistry(
                60_000L,
                5_000L,
                now::get);

        assertTrue(registry.register("session-1", 1001L, "alice"));
        assertFalse(registry.markSeek("session-1", 1002L, "alice"));
        assertFalse(registry.markSeek("session-1", 1001L, "bob"));
        assertTrue(registry.markSeek("session-1", 1001L, "alice"));
        assertTrue(registry.wasRecentlySeeking("session-1", 1001L, "alice"));
    }

    @Test
    public void expiresRecentSeekWindowWithoutExpiringActiveSession() {
        AtomicLong now = new AtomicLong(1_000L);
        MediaPlaybackSessionRegistry registry = new MediaPlaybackSessionRegistry(
                60_000L,
                5_000L,
                now::get);

        registry.register("session-1", 1001L, "alice");
        registry.markSeek("session-1", 1001L, "alice");
        now.addAndGet(5_001L);

        assertFalse(registry.wasRecentlySeeking("session-1", 1001L, "alice"));
        assertTrue(registry.touch("session-1", 1001L, "alice"));
    }
}
