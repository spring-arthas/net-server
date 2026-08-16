package com.alibaba.server.nio.service.file.security;

import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 解析 HMAC 令牌密钥，保证部署环境不会退回公开占位值。
 */
public final class TokenSecretResolver {
    private static final String LOCAL_DIRECTORY = ".net-server";

    private TokenSecretResolver() {
    }

    /**
     * 按环境变量、配置文件、本机私密文件的优先级解析密钥。
     *
     * @param config 配置项
     * @param environment 环境变量读取器
     * @param environmentName 环境变量名
     * @param configKey 配置项名
     * @param localSecretSupplier 本机密钥生成/读取器
     * @param description 密钥用途描述
     * @return 非空且非公开默认值的密钥
     */
    public static String resolve(
            Map<String, Object> config,
            Function<String, String> environment,
            String environmentName,
            String configKey,
            Supplier<String> localSecretSupplier,
            String description) {
        String environmentSecret = environment == null || environmentName == null
                ? null : environment.apply(environmentName);
        if (isUsable(environmentSecret)) {
            return environmentSecret.trim();
        }

        String configuredSecret = config == null || configKey == null || config.get(configKey) == null
                ? null : String.valueOf(config.get(configKey));
        if (isUsable(configuredSecret)) {
            return configuredSecret.trim();
        }

        String localSecret = localSecretSupplier == null ? null : localSecretSupplier.get();
        if (isUsable(localSecret)) {
            return localSecret.trim();
        }

        throw new IllegalStateException("未配置安全的" + description + "密钥");
    }

    /**
     * 读取或生成某种用途的本机密钥。
     *
     * @param fileName 密钥文件名
     * @return 本机密钥
     */
    public static String loadOrCreateLocal(String fileName) {
        if (fileName == null || fileName.trim().isEmpty() || fileName.contains("/") || fileName.contains("\\")) {
            throw new IllegalArgumentException("本机密钥文件名无效");
        }
        String userHome = System.getProperty("user.home");
        if (userHome == null || userHome.trim().isEmpty()) {
            throw new IllegalStateException("无法确定本机密钥目录");
        }
        return LocalSessionSecretStore.loadOrCreate(
                Paths.get(userHome, LOCAL_DIRECTORY, fileName.trim()));
    }

    /**
     * 判断密钥是否为空或公开占位值。
     */
    public static boolean isUsable(String value) {
        if (value == null || value.trim().isEmpty()) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return !"change-me".equals(normalized)
                && !"change-me-transfer-secret".equals(normalized)
                && !"change-me-session-secret".equals(normalized)
                && !"changeme".equals(normalized)
                && !"default".equals(normalized);
    }
}
