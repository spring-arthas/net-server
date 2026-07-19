package com.alibaba.server.nio.media;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.server.nio.repository.file.repository.dataobject.FileDo;
import com.alibaba.server.nio.repository.file.service.FileService;
import com.alibaba.server.nio.repository.file.service.dto.FileDto;
import com.alibaba.server.nio.repository.file.service.dto.FilePageDto;
import com.alibaba.server.nio.repository.file.service.dto.FileTaskDto;
import com.alibaba.server.nio.repository.file.service.param.FileQueryParam;
import com.alibaba.server.nio.repository.file.service.param.FileUpdateParam;
import com.alibaba.server.nio.repository.user.service.dto.UserDTO;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class MediaStreamHandlerIntegrationTest {
    private static final long FILE_ID = 1001L;
    private static final String USER_NAME = "alice";

    private HttpServer server;
    private File rootDir;
    private File mediaFile;
    private byte[] mediaBytes;
    private String playUrl;

    @Before
    public void setUp() throws Exception {
        rootDir = Files.createTempDirectory("media-stream-handler").toFile();
        mediaFile = new File(rootDir, "sample.mp4");
        mediaBytes = new byte[4096];
        for (int i = 0; i < mediaBytes.length; i++) {
            mediaBytes[i] = (byte) (i % 251);
        }
        Files.write(mediaFile.toPath(), mediaBytes);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        MediaTokenService tokenService = new MediaTokenService("test-secret", 60);
        MediaAccessService accessService = new MediaAccessService(
                new FakeFileService(),
                new SafeFileResolver(rootDir.getAbsolutePath()),
                tokenService,
                "127.0.0.1",
                port);
        MediaStreamHandler handler = new MediaStreamHandler(accessService, tokenService, 256);
        server.createContext("/media/play-url", handler);
        server.createContext("/media/stream", handler);
        server.createContext("/media/seek", handler);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();

        HttpResult result = request("GET", "http://127.0.0.1:" + port + "/media/play-url/" + FILE_ID + "?userName=" + USER_NAME, null);
        assertEquals(200, result.status);
        JSONObject body = JSON.parseObject(new String(result.body, "UTF-8"));
        playUrl = body.getJSONObject("data").getString("playUrl");
        assertNotNull(playUrl);
        assertEquals(mediaBytes.length, body.getJSONObject("data").getLongValue("fileSize"));
        assertEquals("video/mp4", body.getJSONObject("data").getString("mimeType"));
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        deleteRecursively(rootDir);
    }

    @Test
    public void returnsHeadMetadataForAvPlayerProbe() throws Exception {
        HttpResult result = request("HEAD", playUrl, null);

        assertEquals(200, result.status);
        assertEquals("bytes", result.header("Accept-Ranges"));
        assertEquals("video/mp4", result.header("Content-Type"));
        assertEquals(0, result.body.length);
    }

    @Test
    public void streamsFullContentWithoutRange() throws Exception {
        HttpResult result = request("GET", playUrl, null);

        assertEquals(200, result.status);
        assertEquals("bytes", result.header("Accept-Ranges"));
        assertEquals("video/mp4", result.header("Content-Type"));
        assertEquals(String.valueOf(mediaBytes.length), result.header("Content-Length"));
        assertArrayEquals(mediaBytes, result.body);
    }

    @Test
    public void streamsOpenEndedRange() throws Exception {
        HttpResult result = request("GET", playUrl, "bytes=1024-");

        assertEquals(206, result.status);
        assertEquals("bytes 1024-4095/4096", result.header("Content-Range"));
        assertEquals("3072", result.header("Content-Length"));
        assertArrayEquals(Arrays.copyOfRange(mediaBytes, 1024, 4096), result.body);
    }

    @Test
    public void streamsExplicitRange() throws Exception {
        HttpResult result = request("GET", playUrl, "bytes=128-639");

        assertEquals(206, result.status);
        assertEquals("bytes 128-639/4096", result.header("Content-Range"));
        assertEquals("512", result.header("Content-Length"));
        assertArrayEquals(Arrays.copyOfRange(mediaBytes, 128, 640), result.body);
    }

    @Test
    public void streamsSuffixRange() throws Exception {
        HttpResult result = request("GET", playUrl, "bytes=-256");

        assertEquals(206, result.status);
        assertEquals("bytes 3840-4095/4096", result.header("Content-Range"));
        assertEquals("256", result.header("Content-Length"));
        assertArrayEquals(Arrays.copyOfRange(mediaBytes, 3840, 4096), result.body);
    }

    @Test
    public void rejectsInvalidRangeWith416() throws Exception {
        HttpResult result = request("GET", playUrl, "bytes=4096-5000");

        assertEquals(416, result.status);
        assertEquals("bytes */4096", result.header("Content-Range"));
        assertEquals("bytes", result.header("Accept-Ranges"));
    }

    @Test
    public void rejectsTamperedTokenWith403() throws Exception {
        HttpResult result = request("GET", playUrl + "tampered", "bytes=0-1");

        assertEquals(403, result.status);
    }

    @Test
    public void rejectsWrongUserWhenCreatingPlayUrl() throws Exception {
        int port = server.getAddress().getPort();
        HttpResult result = request("GET", "http://127.0.0.1:" + port + "/media/play-url/" + FILE_ID + "?userName=bob", null);

        assertEquals(403, result.status);
    }

    @Test
    public void registersPlaybackSessionAndAcceptsSeekNotification() throws Exception {
        int port = server.getAddress().getPort();
        String sessionId = "playback-session-1";
        HttpResult playResult = request(
                "GET",
                "http://127.0.0.1:" + port + "/media/play-url/" + FILE_ID
                        + "?userName=" + USER_NAME + "&sessionId=" + sessionId,
                null);

        assertEquals(200, playResult.status);
        JSONObject body = JSON.parseObject(new String(playResult.body, "UTF-8"));
        String sessionPlayUrl = body.getJSONObject("data").getString("playUrl");
        assertTrue(sessionPlayUrl.contains("sessionId=" + sessionId));

        HttpResult seekResult = request(
                "POST",
                "http://127.0.0.1:" + port + "/media/seek/" + FILE_ID
                        + "?userName=" + USER_NAME + "&sessionId=" + sessionId + "&targetSeconds=12.5",
                null);

        assertEquals(204, seekResult.status);
        assertEquals(0, seekResult.body.length);
    }

    @Test
    public void servesConcurrentIndependentRangeRequests() throws Exception {
        ExecutorService executorService = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> tasks = Arrays.asList(
                    rangeTask("bytes=0-127", 0, 128),
                    rangeTask("bytes=128-255", 128, 256),
                    rangeTask("bytes=256-511", 256, 512),
                    rangeTask("bytes=512-1023", 512, 1024),
                    rangeTask("bytes=1024-1535", 1024, 1536),
                    rangeTask("bytes=1536-2047", 1536, 2048),
                    rangeTask("bytes=2048-3071", 2048, 3072),
                    rangeTask("bytes=3072-4095", 3072, 4096));
            List<Future<Boolean>> futures = executorService.invokeAll(tasks);
            for (Future<Boolean> future : futures) {
                assertEquals(Boolean.TRUE, future.get());
            }
        } finally {
            executorService.shutdownNow();
        }
    }

    private Callable<Boolean> rangeTask(final String rangeHeader, final int startInclusive, final int endExclusive) {
        return new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                HttpResult result = request("GET", playUrl, rangeHeader);
                assertEquals(206, result.status);
                assertArrayEquals(Arrays.copyOfRange(mediaBytes, startInclusive, endExclusive), result.body);
                return Boolean.TRUE;
            }
        };
    }

    private HttpResult request(String method, String url, String rangeHeader) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        if (rangeHeader != null) {
            connection.setRequestProperty("Range", rangeHeader);
        }
        int status = connection.getResponseCode();
        InputStream inputStream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        byte[] body = inputStream == null ? new byte[0] : readAll(inputStream);
        return new HttpResult(status, connection.getHeaderFields(), body);
    }

    private byte[] readAll(InputStream inputStream) throws Exception {
        try (InputStream in = inputStream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }

    private class FakeFileService implements FileService {
        @Override
        public FileDto getFileById(FileQueryParam fileQueryParam) {
            if (FILE_ID != fileQueryParam.getId()) {
                return null;
            }
            FileDto fileDto = new FileDto();
            fileDto.setId(FILE_ID);
            fileDto.setFileName("sample.mp4");
            fileDto.setFilePath(mediaFile.getAbsolutePath());
            fileDto.setFileSize((long) mediaBytes.length);
            fileDto.setFileType("mp4");
            fileDto.setIsFile("Y");
            fileDto.setIsExist("Y");
            fileDto.setUserName(USER_NAME);
            return fileDto;
        }

        @Override
        public FileDto getFolderFileTree(FileQueryParam fileQueryParam, String completeFilePath, String relativeFilePath) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FileDto create(FileQueryParam fileQueryParam, String completeFilePath) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FileDto update(FileUpdateParam fileUpdateParam, String originFilePath, String completeFilePath) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FileDto createFile(FileQueryParam fileQueryParam, UserDTO userDTO) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Boolean deleteFile(FileQueryParam fileQueryParam, List<Long> fileIdList) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteFileById(Long fileId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FileDo findFileByPath(String filePath, Integer userId) {
            throw new UnsupportedOperationException();
        }

        @Override public FileDto createDirectory(Long parentId, String dirName, UserDTO userDTO) { throw new UnsupportedOperationException(); }
        @Override public boolean deleteDirectory(Long dirId, UserDTO userDTO) { throw new UnsupportedOperationException(); }
        @Override public FileDto updateDirectory(Long dirId, String newName) { throw new UnsupportedOperationException(); }
        @Override public FileDto moveDirectory(Long dirId, Long targetParentId) { throw new UnsupportedOperationException(); }
        @Override public boolean isDirectory(Long id) { throw new UnsupportedOperationException(); }
        @Override public String buildDirectoryPath(Long dirId) { throw new UnsupportedOperationException(); }
        @Override public boolean existsSameName(Long parentId, String dirName, Long excludeId) { throw new UnsupportedOperationException(); }
        @Override public FilePageDto listFiles(FileQueryParam fileQueryParam) { throw new UnsupportedOperationException(); }
        @Override public FileDto getFileDetail(Long fileId) { throw new UnsupportedOperationException(); }
        @Override public boolean deleteFileWithFs(Long fileId) { throw new UnsupportedOperationException(); }
        @Override public String validateDirectory(Long dirId) { throw new UnsupportedOperationException(); }
        @Override public String ensureUploadDirectory(Long dirId, Integer userId, String userName) { throw new UnsupportedOperationException(); }
        @Override public FileDto ensureChatAttachmentDirectory(Integer userId, String userName) { throw new UnsupportedOperationException(); }
        @Override public FileDto handleUserTwoLevelDirectory(UserDTO userDTO) { throw new UnsupportedOperationException(); }
        @Override public FileDo createByTask(FileTaskDto fileTaskDto) { throw new UnsupportedOperationException(); }
        @Override public FileDto renameFile(Long fileId, String newFileName) { throw new UnsupportedOperationException(); }
    }

    private static class HttpResult {
        private final int status;
        private final Map<String, List<String>> headers;
        private final byte[] body;

        private HttpResult(int status, Map<String, List<String>> headers, byte[] body) {
            this.status = status;
            this.headers = headers;
            this.body = body;
        }

        private String header(String name) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)
                        && entry.getValue() != null && !entry.getValue().isEmpty()) {
                    return entry.getValue().get(0);
                }
            }
            return null;
        }
    }
}
