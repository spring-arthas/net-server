package com.alibaba.server.nio.service.file;

import com.alibaba.server.common.BasicConstant;
import org.apache.commons.lang.StringUtils;

import java.util.Locale;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

    /** Windows 仅支持按配置顺序返回主、备用存储根目录。 */
    public static List<String> resolveWindowsRoots(Map<String, Object> configuration) {
        String osName = value(configuration, BasicConstant.OS_NAME);
        if (!osName.toLowerCase(Locale.ROOT).contains("win")) {
            return Collections.emptyList();
        }
        List<String> roots = new ArrayList<>();
        addIfPresent(roots, value(configuration, BasicConstant.NIO_FILE_BASE_PATH_WINDOWS));
        addIfPresent(roots, value(configuration, BasicConstant.NIO_FILE_BASE_PATH_WINDOWS_SECONDARY));
        return roots;
    }

    public static List<String> resolveRoots(Map<String, Object> configuration) {
        List<String> windowsRoots = resolveWindowsRoots(configuration);
        if (!windowsRoots.isEmpty()) {
            return windowsRoots;
        }
        String root = resolveRequired(configuration);
        return StringUtils.isBlank(root)
                ? Collections.<String>emptyList()
                : Collections.singletonList(root);
    }

    private static void addIfPresent(List<String> roots, String root) {
        if (StringUtils.isNotBlank(root) && !roots.contains(root)) {
            roots.add(root);
        }
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
