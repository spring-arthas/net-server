package com.alibaba.server.nio.media;

import com.alibaba.fastjson.JSONObject;
import com.alibaba.server.nio.media.MediaAccessService.MediaAccessException;
import com.alibaba.server.nio.media.MediaAccessService.ResolvedMediaFile;
import com.alibaba.server.nio.media.model.ByteRange;
import com.alibaba.server.nio.media.model.MediaPlayUrl;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class MediaStreamHandler implements HttpHandler {
    private final MediaAccessService accessService;
    private final MediaTokenService tokenService;
    private final int bufferSize;

    public MediaStreamHandler(MediaAccessService accessService, MediaTokenService tokenService, int bufferSize) {
        this.accessService = accessService;
        this.tokenService = tokenService;
        this.bufferSize = bufferSize <= 0 ? 256 * 1024 : bufferSize;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        try {
            if (path.startsWith("/media/play-url/")) {
                handlePlayUrl(exchange);
                return;
            }
            if (path.startsWith("/media/stream/")) {
                handleStream(exchange);
                return;
            }
            sendJson(exchange, 404, "not found", null);
        } catch (Exception e) {
            log.error("media request failed: path={}", path, e);
            if (!exchange.getResponseHeaders().containsKey("Content-Type")) {
                sendJson(exchange, 500, "播放服务异常", null);
            }
        } finally {
            exchange.close();
        }
    }

    private void handlePlayUrl(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "method not allowed", null);
            return;
        }

        try {
            Long fileId = parseFileId(exchange.getRequestURI().getPath(), "/media/play-url/");
            String userName = queryParams(exchange).get("userName");
            MediaPlayUrl playUrl = accessService.createPlayUrl(fileId, userName);
            JSONObject data = new JSONObject();
            data.put("playUrl", playUrl.getPlayUrl());
            data.put("fileId", playUrl.getFileId());
            data.put("fileSize", playUrl.getFileSize());
            data.put("mimeType", playUrl.getMimeType());
            data.put("expiresIn", playUrl.getExpiresIn());
            data.put("playable", playUrl.isPlayable());
            sendJson(exchange, 200, "success", data);
        } catch (MediaAccessException e) {
            JSONObject data = null;
            if (e.getStatusCode() == 400) {
                data = new JSONObject();
                data.put("playable", false);
            }
            sendJson(exchange, e.getStatusCode(), e.getMessage(), data);
        }
    }

    private void handleStream(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            sendSimple(exchange, 405, "method not allowed");
            return;
        }

        try {
            Long fileId = parseFileId(exchange.getRequestURI().getPath(), "/media/stream/");
            String token = queryParams(exchange).get("token");
            MediaTokenService.ValidationResult validation = tokenService.validateToken(token);
            if (!validation.isValid() || !fileId.equals(validation.getFileId())) {
                sendSimple(exchange, 403, "forbidden");
                return;
            }
            ResolvedMediaFile mediaFile = accessService.resolveForStreaming(fileId, validation.getUserName());
            long totalSize = mediaFile.getFile().length();
            String rangeHeader = exchange.getRequestHeaders().getFirst("Range");
            ByteRange range = RangeHeaderParser.parse(rangeHeader, totalSize);
            if (range.isInvalid()) {
                Headers headers = exchange.getResponseHeaders();
                headers.set("Content-Range", "bytes */" + totalSize);
                headers.set("Accept-Ranges", "bytes");
                sendSimple(exchange, 416, "Requested Range Not Satisfiable");
                return;
            }

            Headers headers = exchange.getResponseHeaders();
            headers.set("Accept-Ranges", "bytes");
            headers.set("Content-Type", mediaFile.getMimeType());
            headers.set("Cache-Control", "no-store");
            headers.set("Content-Length", String.valueOf(range.length()));
            int status = range.isPartial() ? 206 : 200;
            if (range.isPartial()) {
                headers.set("Content-Range", "bytes " + range.getStart() + "-" + range.getEnd() + "/" + totalSize);
            }

            if ("HEAD".equalsIgnoreCase(method)) {
                exchange.sendResponseHeaders(status, -1);
                return;
            }

            exchange.sendResponseHeaders(status, range.length());
            streamRange(exchange, mediaFile, range);
        } catch (MediaAccessException e) {
            sendSimple(exchange, e.getStatusCode(), e.getMessage());
        }
    }

    private void streamRange(HttpExchange exchange, ResolvedMediaFile mediaFile, ByteRange range) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(mediaFile.getFile(), "r");
             OutputStream outputStream = exchange.getResponseBody()) {
            raf.seek(range.getStart());
            byte[] buffer = new byte[bufferSize];
            long remaining = range.length();
            while (remaining > 0) {
                int readLength = (int) Math.min(buffer.length, remaining);
                int read = raf.read(buffer, 0, readLength);
                if (read < 0) {
                    break;
                }
                outputStream.write(buffer, 0, read);
                remaining -= read;
            }
        }
    }

    private void sendJson(HttpExchange exchange, int code, String message, Object data) throws IOException {
        JSONObject body = new JSONObject();
        body.put("code", code);
        body.put("message", message);
        if (data != null) {
            body.put("data", data);
        }
        byte[] bytes = body.toJSONString().getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }

    private void sendSimple(HttpExchange exchange, int code, String message) throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/plain; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }

    private Long parseFileId(String path, String prefix) throws MediaAccessException {
        String raw = path.substring(prefix.length());
        if (StringUtils.isBlank(raw)) {
            throw new MediaAccessException(400, "fileId不能为空");
        }
        int slash = raw.indexOf('/');
        String idText = slash >= 0 ? raw.substring(0, slash) : raw;
        try {
            return Long.valueOf(idText);
        } catch (NumberFormatException e) {
            throw new MediaAccessException(400, "fileId格式错误");
        }
    }

    private Map<String, String> queryParams(HttpExchange exchange) throws IOException {
        Map<String, String> result = new HashMap<>();
        String query = exchange.getRequestURI().getRawQuery();
        if (StringUtils.isBlank(query)) {
            return result;
        }
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf('=');
            if (idx < 0) {
                result.put(decode(pair), "");
            } else {
                result.put(decode(pair.substring(0, idx)), decode(pair.substring(idx + 1)));
            }
        }
        return result;
    }

    private String decode(String value) throws IOException {
        return URLDecoder.decode(value, "UTF-8");
    }
}
