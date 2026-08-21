package com.alibaba.server.nio.service.file;

import com.alibaba.server.common.BasicConstant;
import org.apache.commons.lang.StringUtils;

import java.util.Locale;
import java.util.Map;

/**
 * 根据当前服务端操作系统选择文件存储根目录。
 */
public final class StorageRootResolver {

    private StorageRootResolver() {
    }

    public static String resolve(Map<String, Object> configuration) {
        return resolve(configuration, ".");
    }

    public static String resolveRequired(Map<String, Object> configuration) {
        return resolve(configuration, "");
    }

    private static String resolve(Map<String, Object> configuration, String defaultValue) {
        String osName = value(configuration, BasicConstant.OS_NAME);
        String key = osName.toLowerCase(Locale.ROOT).contains("win")
                ? BasicConstant.NIO_FILE_BASE_PATH_WINDOWS
                : BasicConstant.NIO_FILE_BASE_PATH_LINUX_MAC;
        return StringUtils.defaultIfBlank(value(configuration, key), defaultValue).trim();
    }

    private static String value(Map<String, Object> configuration, String key) {
        if (configuration == null || configuration.get(key) == null) {
            return "";
        }
        return String.valueOf(configuration.get(key)).trim();
    }
}
