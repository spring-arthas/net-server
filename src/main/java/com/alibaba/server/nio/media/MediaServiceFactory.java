package com.alibaba.server.nio.media;

import com.alibaba.server.common.BasicConstant;
import com.alibaba.server.nio.core.server.BasicServer;
import com.alibaba.server.nio.repository.file.service.FileService;
import org.apache.commons.lang.StringUtils;

import java.util.Map;

public final class MediaServiceFactory {
    private MediaServiceFactory() {
    }

    public static MediaTokenService tokenService() {
        Map<String, Object> config = BasicServer.getMap();
        return new MediaTokenService(
                stringConfig(config, BasicConstant.MEDIA_STREAM_TOKEN_SECRET, "change-me"),
                longConfig(config, BasicConstant.MEDIA_STREAM_TOKEN_EXPIRE_SECONDS, 300L));
    }

    public static MediaAccessService accessService(MediaTokenService tokenService) {
        Map<String, Object> config = BasicServer.getMap();
        int port = intConfig(config, BasicConstant.NIO_MEDIA_STREAM_PORT, 10188);
        String publicHost = stringConfig(
                config,
                BasicConstant.MEDIA_STREAM_PUBLIC_HOST,
                stringConfig(config, BasicConstant.SERVER_IP, "127.0.0.1"));
        FileService fileService = BasicServer.classPathXmlApplicationContext.getBean(FileService.class);
        return new MediaAccessService(
                fileService,
                new SafeFileResolver(storageRoot(config)),
                tokenService,
                publicHost,
                port);
    }

    public static int streamPort() {
        return intConfig(BasicServer.getMap(), BasicConstant.NIO_MEDIA_STREAM_PORT, 10188);
    }

    public static int streamBufferSize() {
        return intConfig(BasicServer.getMap(), BasicConstant.MEDIA_STREAM_BUFFER_SIZE, 256 * 1024);
    }

    public static int streamMaxThreads() {
        return intConfig(BasicServer.getMap(), BasicConstant.MEDIA_STREAM_MAX_THREADS, 64);
    }

    private static String storageRoot(Map<String, Object> config) {
        Object os = config.get(BasicConstant.OS_NAME);
        if (os != null && os.toString().contains("Win")) {
            return stringConfig(config, BasicConstant.NIO_FILE_BASE_PATH_WINDOWS, ".");
        }
        return stringConfig(config, BasicConstant.NIO_FILE_BASE_PATH_LINUX_MAC, ".");
    }

    private static String stringConfig(Map<String, Object> config, String key, String defaultValue) {
        Object value = config.get(key);
        return value == null || StringUtils.isBlank(value.toString()) ? defaultValue : value.toString().trim();
    }

    private static int intConfig(Map<String, Object> config, String key, int defaultValue) {
        try {
            return Integer.parseInt(stringConfig(config, key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static long longConfig(Map<String, Object> config, String key, long defaultValue) {
        try {
            return Long.parseLong(stringConfig(config, key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
