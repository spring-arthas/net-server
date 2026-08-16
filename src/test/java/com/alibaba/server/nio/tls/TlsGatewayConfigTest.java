package com.alibaba.server.nio.tls;

import com.alibaba.server.common.BasicConstant;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

// [修改] 固定内置 TLS Gateway 的配置、端口映射和敏感信息保护契约。
public class TlsGatewayConfigTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void mapsAllExistingPortsToLoopbackBackends() throws Exception {
        Path keyStore = temporaryFolder.newFile("net-server.p12").toPath();

        TlsGatewayConfig config = TlsGatewayConfig.load(
                configValues(),
                environment("172.21.32.64", keyStore, "private-value"));

        assertTrue(config.isEnabled());
        assertEquals("172.21.32.64", config.getBindAddress().getHostAddress());
        assertEquals(keyStore.toAbsolutePath().normalize(), config.getKeyStorePath());
        assertEquals(4, config.getEndpoints().size());
        assertEndpoint(config, "control", 10086);
        assertEndpoint(config, "upload", 10087);
        assertEndpoint(config, "download", 10088);
        assertEndpoint(config, "media", 10188);
        assertEquals(10_000, config.getHandshakeTimeoutMillis());
        assertEquals(10_000, config.getConnectTimeoutMillis());
        assertEquals(300_000, config.getIdleTimeoutMillis());
        assertEquals(512, config.getMaxConnections());
        assertEquals(65_536, config.getBufferSize());
        assertFalse(config.toString().contains("private-value"));
    }

    @Test
    public void disabledGatewayDoesNotRequireTlsEnvironment() {
        Map<String, Object> values = configValues();
        values.put(BasicConstant.TLS_GATEWAY_ENABLED, "false");

        TlsGatewayConfig config = TlsGatewayConfig.load(values, name -> null);

        assertFalse(config.isEnabled());
        assertTrue(config.getEndpoints().isEmpty());
    }

    @Test
    public void loadsDirectJarConfigurationWithoutTlsEnvironment() throws Exception {
        Path keyStore = temporaryFolder.newFile("net-server.p12").toPath();
        Map<String, Object> values = configValues();
        values.put("TLS.GATEWAY.PUBLIC.IP", "172.21.32.64");
        values.put("TLS.GATEWAY.KEYSTORE.PATH", keyStore.toString());

        // [修改] 直接 java -jar 启动不会经过脚本，非敏感 TLS 参数必须能从 server.properties 读取。
        TlsGatewayConfig config = TlsGatewayConfig.load(values, name -> null);

        assertEquals("172.21.32.64", config.getBindAddress().getHostAddress());
        assertEquals(keyStore.toAbsolutePath().normalize(), config.getKeyStorePath());
    }

    @Test
    public void loadsWindowsKeyStoreForDirectJarStartup() throws Exception {
        Path userHome = temporaryFolder.newFolder("windows-home").toPath();
        Path keyStore = createUserHomeKeyStore(userHome, "tls");
        Map<String, Object> values = configValues();
        values.put(BasicConstant.TLS_GATEWAY_PUBLIC_IP, "172.21.32.64");
        values.put(
                BasicConstant.TLS_GATEWAY_KEYSTORE_PATH_WINDOWS,
                "${user.home}/.net-server/tls/net-server.p12");

        TlsGatewayConfig config = TlsGatewayConfig.load(
                values,
                name -> null,
                systemProperties("Windows 11", userHome));

        assertEquals(keyStore.toAbsolutePath().normalize(), config.getKeyStorePath());
    }

    @Test
    public void loadsMacOsKeyStoreForDirectJarStartup() throws Exception {
        Path userHome = temporaryFolder.newFolder("macos-home").toPath();
        Path keyStore = createUserHomeKeyStore(userHome, "tls-macos");
        Map<String, Object> values = configValues();
        values.put(BasicConstant.TLS_GATEWAY_PUBLIC_IP, "172.21.32.64");
        values.put(
                BasicConstant.TLS_GATEWAY_KEYSTORE_PATH_MACOS,
                "${user.home}/.net-server/tls-macos/net-server.p12");

        TlsGatewayConfig config = TlsGatewayConfig.load(
                values,
                name -> null,
                systemProperties("Mac OS X", userHome));

        assertEquals(keyStore.toAbsolutePath().normalize(), config.getKeyStorePath());
    }

    @Test
    public void loadsLinuxKeyStoreForDirectJarStartup() throws Exception {
        Path userHome = temporaryFolder.newFolder("linux-home").toPath();
        Path keyStore = createUserHomeKeyStore(userHome, "tls-linux");
        Map<String, Object> values = configValues();
        values.put(BasicConstant.TLS_GATEWAY_PUBLIC_IP, "172.21.32.64");
        values.put(
                BasicConstant.TLS_GATEWAY_KEYSTORE_PATH_LINUX,
                "~/.net-server/tls-linux/net-server.p12");

        TlsGatewayConfig config = TlsGatewayConfig.load(
                values,
                name -> null,
                systemProperties("Linux", userHome));

        assertEquals(keyStore.toAbsolutePath().normalize(), config.getKeyStorePath());
    }

    @Test
    public void environmentKeyStoreOverridesOperatingSystemConfiguration() throws Exception {
        Path userHome = temporaryFolder.newFolder("override-home").toPath();
        createUserHomeKeyStore(userHome, "tls");
        Path environmentKeyStore = temporaryFolder.newFile("environment.p12").toPath();
        Map<String, Object> values = configValues();
        values.put(
                BasicConstant.TLS_GATEWAY_KEYSTORE_PATH_WINDOWS,
                "${user.home}/.net-server/tls/net-server.p12");

        TlsGatewayConfig config = TlsGatewayConfig.load(
                values,
                environment("172.21.32.64", environmentKeyStore, ""),
                systemProperties("Windows 11", userHome));

        assertEquals(
                environmentKeyStore.toAbsolutePath().normalize(),
                config.getKeyStorePath());
    }

    @Test
    public void rejectsMissingPublicIp() throws Exception {
        Path keyStore = temporaryFolder.newFile("net-server.p12").toPath();
        assertInvalid(
                configValues(),
                environment(null, keyStore, ""),
                "NET_SERVER_PUBLIC_IP");
    }

    @Test
    public void rejectsMissingKeyStore() {
        assertInvalid(
                configValues(),
                environment("172.21.32.64", null, ""),
                "NET_SERVER_TLS_KEYSTORE");
    }

    @Test
    public void rejectsNonLoopbackPlaintextBackends() throws Exception {
        Map<String, Object> values = configValues();
        values.put(BasicConstant.NIO_BIND_IP, "0.0.0.0");
        Path keyStore = temporaryFolder.newFile("net-server.p12").toPath();

        assertInvalid(
                values,
                environment("172.21.32.64", keyStore, ""),
                BasicConstant.NIO_BIND_IP);
    }

    @Test
    public void rejectsAlternateIpv4LoopbackBackendAddress() throws Exception {
        Map<String, Object> values = configValues();
        values.put(BasicConstant.NIO_BIND_IP, "127.0.0.2");
        Path keyStore = temporaryFolder.newFile("net-server.p12").toPath();

        // [修改] Gateway 端点固定转发 127.0.0.1，配置不能接受另一个回环地址。
        assertInvalid(
                values,
                environment("172.21.32.64", keyStore, ""),
                BasicConstant.NIO_BIND_IP);
    }

    @Test
    public void rejectsIpv6LoopbackMediaBackendAddress() throws Exception {
        Map<String, Object> values = configValues();
        values.put(BasicConstant.NIO_MEDIA_STREAM_BIND_IP, "::1");
        Path keyStore = temporaryFolder.newFile("net-server.p12").toPath();

        assertInvalid(
                values,
                environment("172.21.32.64", keyStore, ""),
                BasicConstant.NIO_MEDIA_STREAM_BIND_IP);
    }

    @Test
    public void rejectsDuplicatePublicPorts() throws Exception {
        Map<String, Object> values = configValues();
        values.put(BasicConstant.NIO_FILE_UPLOAD_PORT, "10086");
        Path keyStore = temporaryFolder.newFile("net-server.p12").toPath();

        assertInvalid(
                values,
                environment("172.21.32.64", keyStore, ""),
                "端口重复");
    }

    @Test
    public void rejectsInvalidConnectionLimit() throws Exception {
        Map<String, Object> values = configValues();
        values.put(BasicConstant.TLS_GATEWAY_MAX_CONNECTIONS, "513");
        Path keyStore = temporaryFolder.newFile("net-server.p12").toPath();

        assertInvalid(
                values,
                environment("172.21.32.64", keyStore, ""),
                BasicConstant.TLS_GATEWAY_MAX_CONNECTIONS);
    }

    @Test
    public void returnsIndependentPasswordCopies() throws Exception {
        Path keyStore = temporaryFolder.newFile("net-server.p12").toPath();
        TlsGatewayConfig config = TlsGatewayConfig.load(
                configValues(),
                environment("172.21.32.64", keyStore, "private-value"));

        char[] first = config.copyKeyStorePassword();
        first[0] = 'X';

        assertEquals('p', config.copyKeyStorePassword()[0]);
    }

    private Map<String, Object> configValues() {
        Map<String, Object> values = new HashMap<>();
        values.put(BasicConstant.NIO_BIND_IP, "127.0.0.1");
        values.put(BasicConstant.NIO_MEDIA_STREAM_BIND_IP, "127.0.0.1");
        values.put(BasicConstant.NIO_TEXT_PORT, "10086");
        values.put(BasicConstant.NIO_FILE_UPLOAD_PORT, "10087");
        values.put(BasicConstant.NIO_FILE_DOWNLOAD_PORT, "10088");
        values.put(BasicConstant.NIO_MEDIA_STREAM_PORT, "10188");
        values.put(BasicConstant.TLS_GATEWAY_ENABLED, "true");
        values.put(BasicConstant.TLS_GATEWAY_HANDSHAKE_TIMEOUT_MILLIS, "10000");
        values.put(BasicConstant.TLS_GATEWAY_CONNECT_TIMEOUT_MILLIS, "10000");
        values.put(BasicConstant.TLS_GATEWAY_IDLE_TIMEOUT_MILLIS, "300000");
        values.put(BasicConstant.TLS_GATEWAY_MAX_CONNECTIONS, "512");
        values.put(BasicConstant.TLS_GATEWAY_BUFFER_SIZE, "65536");
        return values;
    }

    private Function<String, String> environment(String publicIp, Path keyStore, String password) {
        return name -> {
            if ("NET_SERVER_PUBLIC_IP".equals(name)) {
                return publicIp;
            }
            if ("NET_SERVER_TLS_KEYSTORE".equals(name)) {
                return keyStore == null ? null : keyStore.toString();
            }
            if ("NET_SERVER_TLS_KEYSTORE_PASSWORD".equals(name)) {
                return password;
            }
            return null;
        };
    }

    private Function<String, String> systemProperties(String osName, Path userHome) {
        return name -> {
            if ("os.name".equals(name)) {
                return osName;
            }
            if ("user.home".equals(name)) {
                return userHome.toString();
            }
            return null;
        };
    }

    private Path createUserHomeKeyStore(Path userHome, String tlsDirectory) throws Exception {
        Path directory = Files.createDirectories(
                userHome.resolve(".net-server").resolve(tlsDirectory));
        return Files.createFile(directory.resolve("net-server.p12"));
    }

    private void assertEndpoint(TlsGatewayConfig config, String name, int port) {
        TlsGatewayEndpoint endpoint = config.getEndpoints().stream()
                .filter(value -> name.equals(value.getName()))
                .findFirst()
                .orElseThrow(AssertionError::new);
        assertEquals(port, endpoint.getPublicPort());
        assertEquals("127.0.0.1", endpoint.getBackendHost());
        assertEquals(port, endpoint.getBackendPort());
    }

    private void assertInvalid(
            Map<String, Object> values,
            Function<String, String> environment,
            String expectedMessage) {
        try {
            TlsGatewayConfig.load(values, environment);
            fail("expected TlsGatewayConfigurationException");
        } catch (TlsGatewayConfigurationException exception) {
            assertTrue(exception.getMessage(), exception.getMessage().contains(expectedMessage));
        }
    }
}
