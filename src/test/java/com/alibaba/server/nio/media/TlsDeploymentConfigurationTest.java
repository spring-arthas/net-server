package com.alibaba.server.nio.media;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class TlsDeploymentConfigurationTest {

    @Test
    public void lanDefaultsUsePlainTcpWithoutEmbeddedGateway() throws Exception {
        Properties properties = new Properties();
        try (InputStream input = getClass().getResourceAsStream("/server.properties")) {
            assertNotNull(input);
            properties.load(input);
        }

        assertProperty(properties, "NIO.BIND.IP", "0.0.0.0");
        assertProperty(properties, "NIO.MEDIA.STREAM.BIND.IP", "0.0.0.0");
        assertProperty(properties, "MEDIA.STREAM.PUBLIC.HOST", "auto");
        assertProperty(properties, "MEDIA.STREAM.PUBLIC.SCHEME", "http");
        assertProperty(properties, "TLS.GATEWAY.ENABLED", "false");
        assertProperty(properties, "NIO.FILE.BASE.PATH.WINDOWS", "E:\\storage\\upload\\file");
        assertProperty(properties, "NIO.FILE.BASE.PATH.LINUX.MAC", "/Users/hljy/Documents/storages/");
        assertProperty(properties, "TLS.GATEWAY.PUBLIC.IP", "auto");
        assertProperty(properties, "TLS.GATEWAY.KEYSTORE.AUTO.CREATE", "true");
        assertProperty(
                properties,
                "TLS.GATEWAY.KEYSTORE.PATH.MACOS",
                "${user.home}/.net-server/tls/net-server.p12");
        assertProperty(properties, "TLS.GATEWAY.HANDSHAKE.TIMEOUT.MILLIS", "10000");
        assertProperty(properties, "TLS.GATEWAY.CONNECT.TIMEOUT.MILLIS", "10000");
        assertProperty(properties, "TLS.GATEWAY.IDLE.TIMEOUT.MILLIS", "300000");
        assertProperty(properties, "TLS.GATEWAY.MAX.CONNECTIONS", "512");
        assertProperty(properties, "TLS.GATEWAY.BUFFER.SIZE", "65536");
    }

    @Test
    public void standaloneHaproxyDeploymentIsRemoved() {
        assertFalse(Files.exists(Paths.get("deploy/haproxy/haproxy.cfg")));
        assertFalse(Files.exists(Paths.get("deploy/haproxy/README.md")));
    }

    @Test
    public void windowsStartScriptDelegatesMissingTlsFilesToJavaProvisioner() throws IOException {
        String source = new String(
                Files.readAllBytes(Paths.get("scripts/run-net-server-windows.ps1")),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("NET_SERVER_PUBLIC_IP is missing"));
        assertFalse(source.contains("NET_SERVER_TLS_KEYSTORE is missing"));
        assertFalse(source.contains("TLS PKCS12 does not exist"));
        assertTrue(source.contains("resolved by Java"));
        assertTrue(source.contains(".net-server\\runtime"));
        assertTrue(source.contains("Copy-Item -LiteralPath $resolvedJarPath"));
        assertFalse(source.contains("& $javaExecutable -jar $resolvedJarPath"));
    }

    @Test
    public void manualCertificateScriptsUseAppleCompatibleServerLifetime() throws IOException {
        String windowsSource = new String(
                Files.readAllBytes(Paths.get("deploy/tls/generate-local-certs-windows.ps1")),
                StandardCharsets.UTF_8);
        String unixSource = new String(
                Files.readAllBytes(Paths.get("deploy/tls/generate-local-certs.sh")),
                StandardCharsets.UTF_8);

        assertTrue(windowsSource.contains("'x509', '-req', '-sha256', '-days', '397'"));
        assertTrue(unixSource.contains("openssl x509 -req -sha256 -days 397"));
        assertFalse(windowsSource.contains("'-days', '825'"));
        assertFalse(unixSource.contains("-days 825"));
    }

    @Test
    public void localCertificateProfileDeclaresStrictCaAndServerUsage() throws Exception {
        // [修改] 本地 CA 也必须满足严格客户端的 keyUsage 校验，不能只声明 CA:TRUE。
        Path config = Paths.get("deploy/tls/openssl-local.cnf");
        assertTrue(Files.isRegularFile(config));
        String value = new String(Files.readAllBytes(config), StandardCharsets.UTF_8);

        assertTrue(value.contains("basicConstraints = critical, CA:true"));
        assertTrue(value.contains("keyUsage = critical, keyCertSign, cRLSign"));
        assertTrue(value.contains("basicConstraints = critical, CA:false"));
        assertTrue(value.contains("extendedKeyUsage = serverAuth"));
        assertTrue(value.contains("subjectAltName = @server_alt_names"));
    }

    @Test
    public void serverContextOwnsEmbeddedGatewayLifecycleInStartupOrder() throws Exception {
        Path contextFile = Paths.get(
                "src/main/java/com/alibaba/server/nio/core/server/NioServerContext.java");
        String source = new String(Files.readAllBytes(contextFile), StandardCharsets.UTF_8);

        int basicServer = source.indexOf("BasicServer.startupBasicServer()");
        int loadConfig = source.indexOf("TlsGatewayConfig.load(");
        int provisionKeyStore = source.indexOf("new TlsKeyStoreProvisioner().provision(");
        int prepareGateway = source.indexOf("preparingGateway.prepare()");
        int iocContainer = source.indexOf("startupIocContainer()");
        int coreServer = source.indexOf("CoreServer.startupCoreServer()");
        int readiness = source.indexOf("TlsBackendReadinessProbe.await(");
        int startGateway = source.indexOf("preparingGateway.start()");

        assertTrue(basicServer >= 0);
        assertTrue(basicServer < loadConfig);
        assertTrue(loadConfig < provisionKeyStore);
        assertTrue(provisionKeyStore < prepareGateway);
        assertTrue(prepareGateway < iocContainer);
        assertTrue(iocContainer < coreServer);
        assertTrue(coreServer < readiness);
        assertTrue(readiness < startGateway);
        assertTrue(source.contains("Runtime.getRuntime().addShutdownHook"));
        assertTrue(source.contains("shutdownTlsGateway"));

        Path entryFile = Paths.get("src/main/java/com/alibaba/server/NetServer.java");
        String entrySource = new String(Files.readAllBytes(entryFile), StandardCharsets.UTF_8);
        assertTrue(entrySource.contains("System.exit(1)"));
    }

    private void assertProperty(Properties properties, String key, String expected) {
        String value = properties.getProperty(key);
        assertNotNull("缺少配置 " + key, value);
        assertEquals(expected, value.trim());
    }
}
