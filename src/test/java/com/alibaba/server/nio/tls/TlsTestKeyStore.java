package com.alibaba.server.nio.tls;

import org.junit.rules.TemporaryFolder;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;

// [修改] 使用当前 JDK 自带 keytool 生成测试证书，测试不依赖开发机真实私钥。
final class TlsTestKeyStore {
    private TlsTestKeyStore() {
    }

    static Path create(TemporaryFolder temporaryFolder, String password) throws Exception {
        Path keyStore = temporaryFolder.newFile("net-server-" + System.nanoTime() + ".p12").toPath();
        Files.delete(keyStore);
        List<String> command = new ArrayList<>(Arrays.asList(
                keytoolPath().toString(),
                "-genkeypair",
                "-alias", "net-server",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-storetype", "PKCS12",
                "-keystore", keyStore.toString(),
                "-storepass", password,
                "-keypass", password,
                "-dname", "CN=127.0.0.1",
                "-validity", "365",
                "-ext", "SAN=ip:127.0.0.1,dns:localhost",
                "-noprompt"));
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        String output = readAll(process.getInputStream());
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new AssertionError("keytool 生成测试 PKCS12 失败: " + output);
        }
        return keyStore;
    }

    static SSLContext createClientContext(Path keyStorePath, char[] password) throws Exception {
        KeyStore source = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(keyStorePath)) {
            source.load(input, password);
        }
        Certificate certificate = firstCertificate(source);
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        trustStore.setCertificateEntry("net-server", certificate);
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trustStore);
        SSLContext context = SSLContext.getInstance("TLSv1.2");
        context.init(null, trustManagers.getTrustManagers(), new SecureRandom());
        return context;
    }

    private static Certificate firstCertificate(KeyStore keyStore) throws Exception {
        Enumeration<String> aliases = keyStore.aliases();
        while (aliases.hasMoreElements()) {
            Certificate certificate = keyStore.getCertificate(aliases.nextElement());
            if (certificate != null) {
                return certificate;
            }
        }
        throw new AssertionError("测试 PKCS12 中没有证书");
    }

    private static Path keytoolPath() {
        Path javaHome = Paths.get(System.getProperty("java.home"));
        Path direct = javaHome.resolve("bin/keytool");
        if (Files.isExecutable(direct)) {
            return direct;
        }
        Path parent = javaHome.getParent();
        if (parent != null) {
            Path parentKeytool = parent.resolve("bin/keytool");
            if (Files.isExecutable(parentKeytool)) {
                return parentKeytool;
            }
        }
        throw new AssertionError("当前 JDK 未找到 keytool: " + javaHome);
    }

    private static String readAll(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int length;
        while ((length = input.read(buffer)) >= 0) {
            output.write(buffer, 0, length);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
}
