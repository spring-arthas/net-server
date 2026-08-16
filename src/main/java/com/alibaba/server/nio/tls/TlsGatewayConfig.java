package com.alibaba.server.nio.tls;

import com.alibaba.server.common.BasicConstant;
import org.apache.commons.lang.StringUtils;

import java.net.InetAddress;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * 内置 TLS Gateway 的已校验运行配置。
 */
public final class TlsGatewayConfig {
    private static final int DEFAULT_HANDSHAKE_TIMEOUT_MILLIS = 10_000;
    private static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final int DEFAULT_IDLE_TIMEOUT_MILLIS = 300_000;
    private static final int DEFAULT_MAX_CONNECTIONS = 512;
    private static final int DEFAULT_BUFFER_SIZE = 65_536;
    private static final int MAX_CONNECTIONS = 512;
    private static final int MIN_BUFFER_SIZE = 4_096;
    private static final int MAX_BUFFER_SIZE = 1_048_576;
    private static final String DEFAULT_KEYSTORE_PATH =
            "${user.home}/.net-server/tls/net-server.p12";

    private final boolean enabled;
    private final InetAddress bindAddress;
    private final Path keyStorePath;
    private final char[] keyStorePassword;
    private final boolean keyStoreAutoCreate;
    private final List<TlsGatewayEndpoint> endpoints;
    private final int handshakeTimeoutMillis;
    private final int connectTimeoutMillis;
    private final int idleTimeoutMillis;
    private final int maxConnections;
    private final int bufferSize;

    private TlsGatewayConfig(
            boolean enabled,
            InetAddress bindAddress,
            Path keyStorePath,
            char[] keyStorePassword,
            boolean keyStoreAutoCreate,
            List<TlsGatewayEndpoint> endpoints,
            int handshakeTimeoutMillis,
            int connectTimeoutMillis,
            int idleTimeoutMillis,
            int maxConnections,
            int bufferSize) {
        this.enabled = enabled;
        this.bindAddress = bindAddress;
        this.keyStorePath = keyStorePath;
        this.keyStorePassword = keyStorePassword.clone();
        this.keyStoreAutoCreate = keyStoreAutoCreate;
        this.endpoints = Collections.unmodifiableList(new ArrayList<>(endpoints));
        this.handshakeTimeoutMillis = handshakeTimeoutMillis;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.idleTimeoutMillis = idleTimeoutMillis;
        this.maxConnections = maxConnections;
        this.bufferSize = bufferSize;
    }

    /**
     * 从服务配置和进程环境构建 TLS Gateway 配置。
     *
     * @param values 服务配置
     * @param environment 环境变量读取器
     * @return 已校验配置
     */
    public static TlsGatewayConfig load(
            Map<String, Object> values,
            Function<String, String> environment) {
        return load(values, environment, System::getProperty);
    }

    static TlsGatewayConfig load(
            Map<String, Object> values,
            Function<String, String> environment,
            Function<String, String> systemProperty) {
        return load(
                values,
                environment,
                systemProperty,
                value -> new TlsNetworkAddressResolver().resolve(value));
    }

    static TlsGatewayConfig load(
            Map<String, Object> values,
            Function<String, String> environment,
            Function<String, String> systemProperty,
            Function<String, InetAddress> addressResolver) {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(systemProperty, "systemProperty");
        Objects.requireNonNull(addressResolver, "addressResolver");
        boolean enabled = booleanValue(values, BasicConstant.TLS_GATEWAY_ENABLED, true);
        if (!enabled) {
            return disabled();
        }

        InetAddress bindAddress = addressResolver.apply(environmentOrConfig(
                values,
                environment,
                "NET_SERVER_PUBLIC_IP",
                BasicConstant.TLS_GATEWAY_PUBLIC_IP,
                TlsNetworkAddressResolver.AUTO));
        Path keyStorePath = keyStorePath(keyStoreValue(
                values,
                environment,
                systemProperty),
                systemProperty.apply("user.home"));
        String passwordValue = environment.apply("NET_SERVER_TLS_KEYSTORE_PASSWORD");
        char[] keyStorePassword = passwordValue == null ? new char[0] : passwordValue.toCharArray();
        boolean keyStoreAutoCreate = environmentBooleanValue(
                values,
                environment,
                "NET_SERVER_TLS_KEYSTORE_AUTO_CREATE",
                BasicConstant.TLS_GATEWAY_KEYSTORE_AUTO_CREATE,
                true);

        requireLoopback(values, BasicConstant.NIO_BIND_IP);
        requireLoopback(values, BasicConstant.NIO_MEDIA_STREAM_BIND_IP);

        int controlPort = portValue(values, BasicConstant.NIO_TEXT_PORT);
        int uploadPort = portValue(values, BasicConstant.NIO_FILE_UPLOAD_PORT);
        int downloadPort = portValue(values, BasicConstant.NIO_FILE_DOWNLOAD_PORT);
        int mediaPort = portValue(values, BasicConstant.NIO_MEDIA_STREAM_PORT);
        requireUniquePorts(controlPort, uploadPort, downloadPort, mediaPort);

        List<TlsGatewayEndpoint> endpoints = Arrays.asList(
                endpoint("control", controlPort),
                endpoint("upload", uploadPort),
                endpoint("download", downloadPort),
                endpoint("media", mediaPort));
        int handshakeTimeoutMillis = intValue(
                values,
                BasicConstant.TLS_GATEWAY_HANDSHAKE_TIMEOUT_MILLIS,
                DEFAULT_HANDSHAKE_TIMEOUT_MILLIS,
                1,
                Integer.MAX_VALUE);
        int connectTimeoutMillis = intValue(
                values,
                BasicConstant.TLS_GATEWAY_CONNECT_TIMEOUT_MILLIS,
                DEFAULT_CONNECT_TIMEOUT_MILLIS,
                1,
                Integer.MAX_VALUE);
        int idleTimeoutMillis = intValue(
                values,
                BasicConstant.TLS_GATEWAY_IDLE_TIMEOUT_MILLIS,
                DEFAULT_IDLE_TIMEOUT_MILLIS,
                1,
                Integer.MAX_VALUE);
        int maxConnections = intValue(
                values,
                BasicConstant.TLS_GATEWAY_MAX_CONNECTIONS,
                DEFAULT_MAX_CONNECTIONS,
                1,
                MAX_CONNECTIONS);
        int bufferSize = intValue(
                values,
                BasicConstant.TLS_GATEWAY_BUFFER_SIZE,
                DEFAULT_BUFFER_SIZE,
                MIN_BUFFER_SIZE,
                MAX_BUFFER_SIZE);

        return new TlsGatewayConfig(
                true,
                bindAddress,
                keyStorePath,
                keyStorePassword,
                keyStoreAutoCreate,
                endpoints,
                handshakeTimeoutMillis,
                connectTimeoutMillis,
                idleTimeoutMillis,
                maxConnections,
                bufferSize);
    }

    private static TlsGatewayConfig disabled() {
        return new TlsGatewayConfig(
                false,
                null,
                null,
                new char[0],
                false,
                Collections.emptyList(),
                DEFAULT_HANDSHAKE_TIMEOUT_MILLIS,
                DEFAULT_CONNECT_TIMEOUT_MILLIS,
                DEFAULT_IDLE_TIMEOUT_MILLIS,
                DEFAULT_MAX_CONNECTIONS,
                DEFAULT_BUFFER_SIZE);
    }

    private static TlsGatewayEndpoint endpoint(String name, int port) {
        return new TlsGatewayEndpoint(name, port, BasicConstant.SERVER_LOCAL_LOOPBACK, port);
    }

    private static String environmentOrConfig(
            Map<String, Object> values,
            Function<String, String> environment,
            String environmentName,
            String configKey,
            String defaultValue) {
        String environmentValue = environment.apply(environmentName);
        if (StringUtils.isNotBlank(environmentValue)) {
            return environmentValue.trim();
        }
        String configValue = stringValue(values, configKey, defaultValue);
        // [修改] 直接 java -jar 启动不会经过脚本，允许从 server.properties 读取非敏感参数。
        return StringUtils.isBlank(configValue) ? defaultValue : configValue;
    }

    private static String keyStoreValue(
            Map<String, Object> values,
            Function<String, String> environment,
            Function<String, String> systemProperty) {
        String environmentValue = environment.apply("NET_SERVER_TLS_KEYSTORE");
        if (StringUtils.isNotBlank(environmentValue)) {
            return environmentValue.trim();
        }

        String operatingSystemKey = keyStoreConfigKey(systemProperty.apply("os.name"));
        String configValue = stringValue(values, operatingSystemKey, null);
        if (StringUtils.isBlank(configValue)
                && !BasicConstant.TLS_GATEWAY_KEYSTORE_PATH.equals(operatingSystemKey)) {
            configValue = stringValue(values, BasicConstant.TLS_GATEWAY_KEYSTORE_PATH, null);
        }
        if (StringUtils.isBlank(configValue)) {
            configValue = DEFAULT_KEYSTORE_PATH;
        }
        return configValue;
    }

    private static String keyStoreConfigKey(String osName) {
        if (StringUtils.isBlank(osName)) {
            return BasicConstant.TLS_GATEWAY_KEYSTORE_PATH;
        }
        String normalized = osName.toLowerCase(Locale.ROOT);
        if (normalized.contains("mac") || normalized.contains("darwin")) {
            return BasicConstant.TLS_GATEWAY_KEYSTORE_PATH_MACOS;
        }
        if (normalized.contains("win")) {
            return BasicConstant.TLS_GATEWAY_KEYSTORE_PATH_WINDOWS;
        }
        if (normalized.contains("linux") || normalized.contains("nux")) {
            return BasicConstant.TLS_GATEWAY_KEYSTORE_PATH_LINUX;
        }
        return BasicConstant.TLS_GATEWAY_KEYSTORE_PATH;
    }

    private static Path keyStorePath(String value, String userHome) {
        try {
            return Paths.get(expandUserHome(value, userHome)).toAbsolutePath().normalize();
        } catch (InvalidPathException exception) {
            throw new TlsGatewayConfigurationException(
                    "NET_SERVER_TLS_KEYSTORE 路径无效",
                    exception);
        }
    }

    private static String expandUserHome(String value, String userHome) {
        String resolved = value.trim();
        boolean requiresUserHome = resolved.contains("${user.home}")
                || resolved.contains("%USERPROFILE%")
                || "~".equals(resolved)
                || resolved.startsWith("~/")
                || resolved.startsWith("~\\");
        if (!requiresUserHome) {
            return resolved;
        }
        if (StringUtils.isBlank(userHome)) {
            throw new TlsGatewayConfigurationException(
                    "无法展开 NET_SERVER_TLS_KEYSTORE 路径：user.home 为空");
        }
        resolved = resolved.replace("${user.home}", userHome);
        resolved = resolved.replace("%USERPROFILE%", userHome);
        if ("~".equals(resolved)) {
            return userHome;
        }
        if (resolved.startsWith("~/") || resolved.startsWith("~\\")) {
            return Paths.get(userHome, resolved.substring(2)).toString();
        }
        return resolved;
    }

    private static void requireLoopback(Map<String, Object> values, String key) {
        String value = stringValue(values, key, null);
        if (StringUtils.isBlank(value)) {
            throw new TlsGatewayConfigurationException(key + " 不能为空");
        }
        // [修改] 转发端点固定连接 127.0.0.1，后端监听地址必须与它完全一致。
        if (!BasicConstant.SERVER_LOCAL_LOOPBACK.equals(value)) {
            throw new TlsGatewayConfigurationException(
                    key + " 必须监听 " + BasicConstant.SERVER_LOCAL_LOOPBACK + ": " + value);
        }
    }

    private static int portValue(Map<String, Object> values, String key) {
        return intValue(values, key, null, 1, 65_535);
    }

    private static int intValue(
            Map<String, Object> values,
            String key,
            Integer defaultValue,
            int minimum,
            int maximum) {
        String value = stringValue(values, key, defaultValue == null ? null : defaultValue.toString());
        if (StringUtils.isBlank(value)) {
            throw new TlsGatewayConfigurationException(key + " 不能为空");
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < minimum || parsed > maximum) {
                throw new TlsGatewayConfigurationException(
                        key + " 超出范围 " + minimum + ".." + maximum + ": " + value);
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new TlsGatewayConfigurationException(key + " 不是有效整数: " + value, exception);
        }
    }

    private static boolean booleanValue(
            Map<String, Object> values,
            String key,
            boolean defaultValue) {
        String value = stringValue(values, key, Boolean.toString(defaultValue));
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new TlsGatewayConfigurationException(key + " 必须是 true 或 false: " + value);
    }

    private static boolean environmentBooleanValue(
            Map<String, Object> values,
            Function<String, String> environment,
            String environmentName,
            String configKey,
            boolean defaultValue) {
        String environmentValue = environment.apply(environmentName);
        if (StringUtils.isBlank(environmentValue)) {
            return booleanValue(values, configKey, defaultValue);
        }
        if ("true".equalsIgnoreCase(environmentValue.trim())) {
            return true;
        }
        if ("false".equalsIgnoreCase(environmentValue.trim())) {
            return false;
        }
        throw new TlsGatewayConfigurationException(
                environmentName + " 必须是 true 或 false: " + environmentValue);
    }

    private static String stringValue(
            Map<String, Object> values,
            String key,
            String defaultValue) {
        Object value = values.get(key);
        if (value == null) {
            return defaultValue;
        }
        return value.toString().trim();
    }

    private static void requireUniquePorts(int... ports) {
        Set<Integer> unique = new HashSet<>();
        for (int port : ports) {
            if (!unique.add(port)) {
                throw new TlsGatewayConfigurationException("TLS Gateway 端口重复: " + port);
            }
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public InetAddress getBindAddress() {
        return bindAddress;
    }

    public Path getKeyStorePath() {
        return keyStorePath;
    }

    public char[] copyKeyStorePassword() {
        return keyStorePassword.clone();
    }

    public boolean isKeyStoreAutoCreate() {
        return keyStoreAutoCreate;
    }

    public List<TlsGatewayEndpoint> getEndpoints() {
        return endpoints;
    }

    public int getHandshakeTimeoutMillis() {
        return handshakeTimeoutMillis;
    }

    public int getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    public int getIdleTimeoutMillis() {
        return idleTimeoutMillis;
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public int getBufferSize() {
        return bufferSize;
    }

    @Override
    public String toString() {
        return "TlsGatewayConfig{" +
                "enabled=" + enabled +
                ", bindAddress=" + bindAddress +
                ", keyStorePath=" + keyStorePath +
                ", keyStoreAutoCreate=" + keyStoreAutoCreate +
                ", endpoints=" + endpoints +
                ", handshakeTimeoutMillis=" + handshakeTimeoutMillis +
                ", connectTimeoutMillis=" + connectTimeoutMillis +
                ", idleTimeoutMillis=" + idleTimeoutMillis +
                ", maxConnections=" + maxConnections +
                ", bufferSize=" + bufferSize +
                '}';
    }
}
