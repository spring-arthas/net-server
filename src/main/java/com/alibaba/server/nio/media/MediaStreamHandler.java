package com.alibaba.server.nio.media;

import com.alibaba.fastjson.JSONObject;
import com.alibaba.server.nio.media.MediaAccessService.MediaAccessException;
import com.alibaba.server.nio.media.MediaAccessService.ResolvedMediaFile;
import com.alibaba.server.nio.media.model.ByteRange;
import com.alibaba.server.nio.media.model.MediaPlayUrl;
import com.alibaba.server.nio.service.file.security.TransferTokenService;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.jcodec.api.FrameGrab;
import org.jcodec.api.JCodecException;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.io.SeekableByteChannel;
import org.jcodec.common.model.Picture;
import org.jcodec.scale.AWTUtil;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class MediaStreamHandler implements HttpHandler {
    private static final int THUMBNAIL_MAX_WIDTH = 960;
    private static final int THUMBNAIL_MAX_HEIGHT = 540;
    private final MediaAccessService accessService;
    private final MediaTokenService tokenService;
    private final TransferTokenService transferTokenService;
    private final MediaPlaybackSessionRegistry sessionRegistry;
    private final int bufferSize;

    public MediaStreamHandler(MediaAccessService accessService,
                              MediaTokenService tokenService,
                              TransferTokenService transferTokenService,
                              int bufferSize) {
        this(accessService, tokenService, transferTokenService, new MediaPlaybackSessionRegistry(), bufferSize);
    }

    /** 兼容旧测试和旧内部调用，播放地址入口仍使用媒体令牌身份。 */
    public MediaStreamHandler(MediaAccessService accessService, MediaTokenService tokenService, int bufferSize) {
        this(accessService, tokenService, null, new MediaPlaybackSessionRegistry(), bufferSize);
    }

    MediaStreamHandler(MediaAccessService accessService,
                       MediaTokenService tokenService,
                       TransferTokenService transferTokenService,
                       MediaPlaybackSessionRegistry sessionRegistry,
                       int bufferSize) {
        this.accessService = accessService;
        this.tokenService = tokenService;
        this.transferTokenService = transferTokenService;
        this.sessionRegistry = sessionRegistry;
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
            if (path.startsWith("/media/thumbnail/")) {
                handleThumbnail(exchange);
                return;
            }
            if (path.startsWith("/media/stream/")) {
                handleStream(exchange);
                return;
            }
            if (path.startsWith("/media/seek/")) {
                handleSeek(exchange);
                return;
            }
            sendJson(exchange, 404, "not found", null);
        } catch (Exception e) {
            if (MediaClientAbortDetector.isClientAbort(e)) {
                log.debug("媒体请求已被客户端取消: path={}", path);
                return;
            }
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
            Map<String, String> params = queryParams(exchange);
            // [修改] 用户身份只取自签名传输令牌，query 中的 userName 不再参与鉴权。
            TransferTokenService.ValidationResult identity = requireTransferIdentity(exchange);
            String userName = identity.getUserName();
            String sessionId = params.get("sessionId");
            MediaPlayUrl playUrl = accessService.createPlayUrl(fileId, identity.getUserId(), userName, sessionId);
            if (StringUtils.isNotBlank(sessionId) && !sessionRegistry.register(sessionId, fileId, userName)) {
                sendJson(exchange, 400, "sessionId格式错误", null);
                return;
            }
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

    private void handleSeek(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "method not allowed", null);
            return;
        }

        try {
            Long fileId = parseFileId(exchange.getRequestURI().getPath(), "/media/seek/");
            Map<String, String> params = queryParams(exchange);
            // [修改] 跳转通知和播放地址使用同一令牌身份，避免伪造会话用户名。
            String userName = requireTransferIdentity(exchange).getUserName();
            String sessionId = params.get("sessionId");
            if (!sessionRegistry.markSeek(sessionId, fileId, userName)) {
                sendJson(exchange, 409, "播放会话不存在或已过期", null);
                return;
            }
            log.debug("收到媒体跳转通知: fileId={}, sessionId={}, targetSeconds={}",
                    fileId, abbreviateSessionId(sessionId), params.get("targetSeconds"));
            exchange.sendResponseHeaders(204, -1);
        } catch (MediaAccessException e) {
            sendJson(exchange, e.getStatusCode(), e.getMessage(), null);
        }
    }

    private void handleThumbnail(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendSimple(exchange, 405, "method not allowed");
            return;
        }

        Long fileId = null;
        try {
            fileId = parseFileId(exchange.getRequestURI().getPath(), "/media/thumbnail/");
            TransferTokenService.ValidationResult identity = requireTransferIdentity(exchange);
            ResolvedMediaFile mediaFile = accessService.resolveForStreaming(
                    fileId, identity.getUserId(), identity.getUserName());
            BufferedImage source = createThumbnailSource(mediaFile);
            if (source == null) {
                sendSimple(exchange, 415, "unsupported preview format");
                return;
            }
            BufferedImage thumbnail = scaleToFit(source, THUMBNAIL_MAX_WIDTH, THUMBNAIL_MAX_HEIGHT);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(thumbnail, "jpg", output);
            byte[] bytes = output.toByteArray();
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", "image/jpeg");
            headers.set("Cache-Control", "private, max-age=300");
            headers.set("Content-Length", String.valueOf(bytes.length));
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream response = exchange.getResponseBody()) {
                response.write(bytes);
            }
            log.debug("生成媒体缩略图成功，fileId={}, width={}, height={}",
                    fileId, thumbnail.getWidth(), thumbnail.getHeight());
        } catch (MediaAccessException e) {
            log.warn("生成媒体缩略图被拒绝，fileId={}，原因={}", fileId, e.getMessage());
            sendSimple(exchange, e.getStatusCode(), e.getMessage());
        } catch (IOException e) {
            log.error("生成媒体缩略图失败，fileId={}，原因={}", fileId, e.getMessage(), e);
            sendSimple(exchange, 500, "缩略图生成失败");
        }
    }

    private BufferedImage createThumbnailSource(ResolvedMediaFile mediaFile) throws IOException {
        String fileName = mediaFile.getFileDto().getFileName();
        File file = mediaFile.getFile();
        if (MediaContentTypeResolver.isPreviewableImage(fileName)) {
            return ImageIO.read(file);
        }
        if (MediaContentTypeResolver.isPlayableVideo(fileName)) {
            try (SeekableByteChannel channel = NIOUtils.readableChannel(file)) {
                Picture picture = FrameGrab.createFrameGrab(channel).getNativeFrame();
                return picture == null ? null : AWTUtil.toBufferedImage(picture);
            } catch (JCodecException e) {
                throw new IOException("无法解析视频首帧", e);
            }
        }
        return null;
    }

    private BufferedImage scaleToFit(BufferedImage source, int maxWidth, int maxHeight) {
        double ratio = Math.min((double) maxWidth / source.getWidth(),
                (double) maxHeight / source.getHeight());
        ratio = Math.min(1.0D, ratio);
        int width = Math.max(1, (int) Math.round(source.getWidth() * ratio));
        int height = Math.max(1, (int) Math.round(source.getHeight() * ratio));
        if (width == source.getWidth() && height == source.getHeight()
                && source.getType() == BufferedImage.TYPE_INT_RGB) {
            return source;
        }
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setColor(java.awt.Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private void handleStream(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            sendSimple(exchange, 405, "method not allowed");
            return;
        }

        try {
            Long fileId = parseFileId(exchange.getRequestURI().getPath(), "/media/stream/");
            Map<String, String> params = queryParams(exchange);
            String token = params.get("token");
            String sessionId = params.get("sessionId");
            MediaTokenService.ValidationResult validation = tokenService.validateToken(token);
            if (!validation.isValid() || !fileId.equals(validation.getFileId())) {
                sendSimple(exchange, 403, "forbidden");
                return;
            }
            if (StringUtils.isNotBlank(sessionId)) {
                sessionRegistry.touch(sessionId, fileId, validation.getUserName());
            }
            ResolvedMediaFile mediaFile = accessService.resolveForStreaming(
                    fileId, validation.getUserId(), validation.getUserName());
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
            streamRange(exchange, mediaFile, range, fileId, validation.getUserName(), sessionId);
        } catch (MediaAccessException e) {
            sendSimple(exchange, e.getStatusCode(), e.getMessage());
        }
    }

    private void streamRange(HttpExchange exchange,
                             ResolvedMediaFile mediaFile,
                             ByteRange range,
                             Long fileId,
                             String userName,
                             String sessionId) throws IOException {
        long sent = 0L;
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
                sent += read;
            }
        } catch (IOException e) {
            if (!MediaClientAbortDetector.isClientAbort(e)) {
                throw e;
            }
            if (sessionRegistry.wasRecentlySeeking(sessionId, fileId, userName)) {
                log.debug("媒体 Range 因进度跳转被取消: fileId={}, sessionId={}, range={}-{}, sent={}",
                        fileId, abbreviateSessionId(sessionId), range.getStart(), range.getEnd(), sent);
            } else {
                log.debug("媒体 Range 被客户端中止: fileId={}, sessionId={}, range={}-{}, sent={}",
                        fileId, abbreviateSessionId(sessionId), range.getStart(), range.getEnd(), sent);
            }
        }
    }

    private String abbreviateSessionId(String sessionId) {
        if (StringUtils.isBlank(sessionId)) {
            return "none";
        }
        return sessionId.length() <= 8 ? sessionId : sessionId.substring(0, 8);
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

    private TransferTokenService.ValidationResult requireTransferIdentity(HttpExchange exchange)
            throws MediaAccessException {
        if (transferTokenService == null) {
            throw new MediaAccessException(401, "登录凭据缺失");
        }
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (StringUtils.isBlank(authorization) || !authorization.startsWith("Bearer ")) {
            throw new MediaAccessException(401, "登录凭据缺失");
        }
        String token = authorization.substring("Bearer ".length()).trim();
        TransferTokenService.ValidationResult validation = transferTokenService.validateToken(token);
        if (!validation.isValid()) {
            throw new MediaAccessException(401, "登录凭据无效或已过期");
        }
        return validation;
    }
}
