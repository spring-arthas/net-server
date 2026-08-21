package com.alibaba.server.nio.media;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MediaContentTypeResolverTest {

    @Test
    public void resolvesSupportedImageMimeTypes() {
        assertEquals("image/jpeg", MediaContentTypeResolver.resolve("photo.JPG"));
        assertEquals("image/png", MediaContentTypeResolver.resolve("screen.png"));
        assertEquals("image/webp", MediaContentTypeResolver.resolve("preview.webp"));
        assertTrue(MediaContentTypeResolver.isPreviewableImage("photo.jpg"));
        assertTrue(MediaContentTypeResolver.isPreviewableMedia("clip.mp4"));
        assertFalse(MediaContentTypeResolver.isPreviewableMedia("notes.pdf"));
    }
}
