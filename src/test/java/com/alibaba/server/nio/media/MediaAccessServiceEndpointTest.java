package com.alibaba.server.nio.media;

import org.junit.Test;

import com.alibaba.server.nio.media.model.MediaPlayUrl;
import com.alibaba.server.nio.repository.file.service.FileService;
import com.alibaba.server.nio.repository.file.service.dto.FileDto;
import com.alibaba.server.nio.repository.file.service.param.FileQueryParam;

import java.lang.reflect.Proxy;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MediaAccessServiceEndpointTest {

    @Test
    public void streamUrlBuilderDoesNotExposeGenericCheckedException() throws Exception {
        Method method = MediaAccessService.class.getDeclaredMethod(
                "buildStreamUrl", Long.class, String.class, String.class);

        assertEquals(0, method.getExceptionTypes().length);
    }

    @Test
    public void buildsHttpsStreamUrlFromConfiguredPublicEndpoint() throws Exception {
        MediaAccessService service = new MediaAccessService(
                null,
                null,
                new MediaTokenService("test-media-secret", 300L),
                "https",
                "media.example.com",
                10188);

        String url = service.buildStreamUrl(77L, "alice", "playback-session");

        assertTrue(url.startsWith("https://media.example.com:10188/media/stream/77?token="));
        assertTrue(url.contains("&sessionId=playback-session"));
    }

    @Test
    public void visibleDynamicReferenceAllowsFriendToCreatePlayUrl() throws Exception {
        FileDto file = new FileDto();
        file.setId(77L);
        file.setFileName("movie.mp4");
        file.setUserName("owner");
        FileService fileService = (FileService) Proxy.newProxyInstance(
                FileService.class.getClassLoader(), new Class<?>[] {FileService.class},
                (proxy, method, args) -> "getFileById".equals(method.getName()) ? file : null);
        java.io.File root = java.nio.file.Files.createTempDirectory("dynamic-media-access").toFile();
        java.nio.file.Files.write(new java.io.File(root, "movie.mp4").toPath(), new byte[] {1});
        file.setFilePath(new java.io.File(root, "movie.mp4").getAbsolutePath());
        MediaAccessService service = new MediaAccessService(
                fileService,
                new SafeFileResolver(root.getAbsolutePath()),
                new MediaTokenService("test-media-secret", 300L),
                "https", "media.example.com", 10188,
                (userId, fileId) -> true);

        MediaPlayUrl result = service.createPlayUrl(77L, 9L, "friend", null);

        assertEquals(Long.valueOf(77L), result.getFileId());
    }
}
