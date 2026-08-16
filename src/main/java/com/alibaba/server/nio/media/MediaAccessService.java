package com.alibaba.server.nio.media;

import com.alibaba.server.nio.media.model.MediaPlayUrl;
import com.alibaba.server.nio.repository.file.service.FileService;
import com.alibaba.server.nio.repository.file.service.dto.FileDto;
import com.alibaba.server.nio.repository.file.service.param.FileQueryParam;
import org.apache.commons.lang.StringUtils;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.function.BiPredicate;

public class MediaAccessService {
    private final FileService fileService;
    private final SafeFileResolver safeFileResolver;
    private final MediaTokenService tokenService;
    private final BiPredicate<Long, Long> dynamicMediaAccessChecker;
    private final String publicScheme;
    private final String publicHost;
    private final int publicPort;

    public MediaAccessService(FileService fileService,
                              SafeFileResolver safeFileResolver,
                              MediaTokenService tokenService,
                              String publicScheme,
                              String publicHost,
                              int publicPort) {
        this(fileService, safeFileResolver, tokenService, publicScheme, publicHost, publicPort, null);
    }

    /**
     * 兼容旧调用方：旧版媒体服务默认使用 HTTP 公网地址。
     */
    public MediaAccessService(FileService fileService,
                              SafeFileResolver safeFileResolver,
                              MediaTokenService tokenService,
                              String publicHost,
                              int publicPort) {
        this(fileService, safeFileResolver, tokenService, "http", publicHost, publicPort, null);
    }

    /**
     * 构造带动态媒体可见性校验的媒体访问服务。
     *
     * @param dynamicMediaAccessChecker 非文件所有者访问动态媒体时的权限查询器
     */
    public MediaAccessService(FileService fileService,
                              SafeFileResolver safeFileResolver,
                              MediaTokenService tokenService,
                              String publicScheme,
                              String publicHost,
                              int publicPort,
                              BiPredicate<Long, Long> dynamicMediaAccessChecker) {
        this.fileService = fileService;
        this.safeFileResolver = safeFileResolver;
        this.tokenService = tokenService;
        this.dynamicMediaAccessChecker = dynamicMediaAccessChecker;
        this.publicScheme = normalizeScheme(publicScheme);
        this.publicHost = publicHost;
        this.publicPort = publicPort;
    }

    public MediaPlayUrl createPlayUrl(Long fileId, String userName) throws MediaAccessException {
        return createPlayUrl(fileId, userName, null);
    }

    public MediaPlayUrl createPlayUrl(Long fileId, String userName, String sessionId) throws MediaAccessException {
        return createPlayUrl(fileId, null, userName, sessionId);
    }

    /**
     * 创建带登录用户 ID 的播放地址。用户 ID 用于动态引用授权，用户名用于兼容旧文件记录。
     */
    public MediaPlayUrl createPlayUrl(Long fileId, Long userId, String userName, String sessionId)
            throws MediaAccessException {
        FileDto fileDto = requireAccessibleFile(fileId, userId, userName);
        if (!MediaContentTypeResolver.isPlayableVideo(fileDto.getFileName())) {
            throw new MediaAccessException(400, "该文件暂不支持在线播放，请下载后播放");
        }

        File file = resolveExistingFile(fileDto);
        try {
            String url = buildStreamUrl(fileId, userId, userName, sessionId);
            return new MediaPlayUrl(
                    fileId,
                    url,
                    file.length(),
                    MediaContentTypeResolver.resolve(fileDto.getFileName()),
                    tokenService.getExpireSeconds(),
                    true);
        } catch (IllegalStateException e) {
            throw new MediaAccessException(500, "生成播放地址失败");
        }
    }

    String buildStreamUrl(Long fileId, String userName, String sessionId) {
        return buildStreamUrl(fileId, null, userName, sessionId);
    }

    String buildStreamUrl(Long fileId, Long userId, String userName, String sessionId) {
        String token = userId == null
                ? tokenService.generateToken(fileId, userName)
                : tokenService.generateToken(fileId, userId, userName);
        String encodedToken = encodeQueryValue(token);
        String url = publicScheme + "://" + publicHost + ":" + publicPort
                + "/media/stream/" + fileId + "?token=" + encodedToken;
        if (StringUtils.isNotBlank(sessionId)) {
            url += "&sessionId=" + encodeQueryValue(sessionId);
        }
        return url;
    }

    private String encodeQueryValue(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            // [修改] UTF-8 是 Java 必须支持的字符集，异常只表示运行环境损坏。
            throw new IllegalStateException("UTF-8编码不可用", e);
        }
    }

    public ResolvedMediaFile resolveForStreaming(Long fileId, String userName) throws MediaAccessException {
        return resolveForStreaming(fileId, null, userName);
    }

    /** 解析媒体文件并按登录用户 ID 校验动态引用权限。 */
    public ResolvedMediaFile resolveForStreaming(Long fileId, Long userId, String userName)
            throws MediaAccessException {
        FileDto fileDto = requireAccessibleFile(fileId, userId, userName);
        File file = resolveExistingFile(fileDto);
        return new ResolvedMediaFile(fileDto, file, MediaContentTypeResolver.resolve(fileDto.getFileName()));
    }

    private FileDto requireAccessibleFile(Long fileId, String userName) throws MediaAccessException {
        return requireAccessibleFile(fileId, null, userName);
    }

    private FileDto requireAccessibleFile(Long fileId, Long userId, String userName) throws MediaAccessException {
        if (fileId == null) {
            throw new MediaAccessException(400, "fileId不能为空");
        }
        FileQueryParam queryParam = new FileQueryParam();
        queryParam.setId(fileId);
        FileDto fileDto = fileService.getFileById(queryParam);
        if (fileDto == null || fileDto.getId() == null) {
            throw new MediaAccessException(404, "文件不存在");
        }
        boolean owner = userId != null && fileDto.getUserId() != null
                && userId.longValue() == fileDto.getUserId().longValue();
        owner = owner || (StringUtils.isNotBlank(fileDto.getUserName())
                && StringUtils.equals(fileDto.getUserName(), userName));
        boolean visibleDynamicReference = !owner && hasVisibleDynamicReference(userId, fileId);
        if (!owner && !visibleDynamicReference) {
            throw new MediaAccessException(403, "无权播放该文件");
        }
        return fileDto;
    }

    private boolean hasVisibleDynamicReference(Long userId, Long fileId) {
        if (userId == null || dynamicMediaAccessChecker == null) {
            return false;
        }
        try {
            return dynamicMediaAccessChecker.test(userId, fileId);
        } catch (RuntimeException e) {
            // [修改] 权限查询失败时拒绝访问，不把数据库异常转换成未捕获的 HTTP 500。
            return false;
        }
    }

    private File resolveExistingFile(FileDto fileDto) throws MediaAccessException {
        try {
            File file = safeFileResolver.resolve(fileDto);
            if (!file.exists() || !file.isFile()) {
                throw new MediaAccessException(404, "文件不存在");
            }
            return file;
        } catch (MediaAccessException e) {
            throw e;
        } catch (Exception e) {
            throw new MediaAccessException(404, "文件不存在");
        }
    }

    private String normalizeScheme(String scheme) {
        String normalized = StringUtils.defaultIfBlank(scheme, "https").trim().toLowerCase();
        if (!"https".equals(normalized) && !"http".equals(normalized)) {
            throw new IllegalArgumentException("unsupported media public scheme");
        }
        return normalized;
    }

    public static class ResolvedMediaFile {
        private final FileDto fileDto;
        private final File file;
        private final String mimeType;

        public ResolvedMediaFile(FileDto fileDto, File file, String mimeType) {
            this.fileDto = fileDto;
            this.file = file;
            this.mimeType = mimeType;
        }

        public FileDto getFileDto() {
            return fileDto;
        }

        public File getFile() {
            return file;
        }

        public String getMimeType() {
            return mimeType;
        }
    }

    public static class MediaAccessException extends Exception {
        private final int statusCode;

        public MediaAccessException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        public int getStatusCode() {
            return statusCode;
        }
    }
}
