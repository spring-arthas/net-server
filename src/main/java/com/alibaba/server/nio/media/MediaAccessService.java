package com.alibaba.server.nio.media;

import com.alibaba.server.nio.media.model.MediaPlayUrl;
import com.alibaba.server.nio.repository.file.service.FileService;
import com.alibaba.server.nio.repository.file.service.dto.FileDto;
import com.alibaba.server.nio.repository.file.service.param.FileQueryParam;
import org.apache.commons.lang.StringUtils;

import java.io.File;
import java.net.URLEncoder;

public class MediaAccessService {
    private final FileService fileService;
    private final SafeFileResolver safeFileResolver;
    private final MediaTokenService tokenService;
    private final String publicHost;
    private final int publicPort;

    public MediaAccessService(FileService fileService,
                              SafeFileResolver safeFileResolver,
                              MediaTokenService tokenService,
                              String publicHost,
                              int publicPort) {
        this.fileService = fileService;
        this.safeFileResolver = safeFileResolver;
        this.tokenService = tokenService;
        this.publicHost = publicHost;
        this.publicPort = publicPort;
    }

    public MediaPlayUrl createPlayUrl(Long fileId, String userName) throws MediaAccessException {
        return createPlayUrl(fileId, userName, null);
    }

    public MediaPlayUrl createPlayUrl(Long fileId, String userName, String sessionId) throws MediaAccessException {
        FileDto fileDto = requireAccessibleFile(fileId, userName);
        if (!MediaContentTypeResolver.isPlayableVideo(fileDto.getFileName())) {
            throw new MediaAccessException(400, "该文件暂不支持在线播放，请下载后播放");
        }

        File file = resolveExistingFile(fileDto);
        String token = tokenService.generateToken(fileId, userName);
        try {
            String encodedToken = URLEncoder.encode(token, "UTF-8");
            String url = "http://" + publicHost + ":" + publicPort + "/media/stream/" + fileId + "?token=" + encodedToken;
            if (StringUtils.isNotBlank(sessionId)) {
                url += "&sessionId=" + URLEncoder.encode(sessionId, "UTF-8");
            }
            return new MediaPlayUrl(
                    fileId,
                    url,
                    file.length(),
                    MediaContentTypeResolver.resolve(fileDto.getFileName()),
                    tokenService.getExpireSeconds(),
                    true);
        } catch (Exception e) {
            throw new MediaAccessException(500, "生成播放地址失败");
        }
    }

    public ResolvedMediaFile resolveForStreaming(Long fileId, String userName) throws MediaAccessException {
        FileDto fileDto = requireAccessibleFile(fileId, userName);
        File file = resolveExistingFile(fileDto);
        return new ResolvedMediaFile(fileDto, file, MediaContentTypeResolver.resolve(fileDto.getFileName()));
    }

    private FileDto requireAccessibleFile(Long fileId, String userName) throws MediaAccessException {
        if (fileId == null) {
            throw new MediaAccessException(400, "fileId不能为空");
        }
        FileQueryParam queryParam = new FileQueryParam();
        queryParam.setId(fileId);
        FileDto fileDto = fileService.getFileById(queryParam);
        if (fileDto == null || fileDto.getId() == null) {
            throw new MediaAccessException(404, "文件不存在");
        }
        if (StringUtils.isNotBlank(fileDto.getUserName()) && !StringUtils.equals(fileDto.getUserName(), userName)) {
            throw new MediaAccessException(403, "无权播放该文件");
        }
        return fileDto;
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
