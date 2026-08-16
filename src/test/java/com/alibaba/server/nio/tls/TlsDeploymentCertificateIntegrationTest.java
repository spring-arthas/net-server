package com.alibaba.server.nio.tls;

import org.junit.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// [修改] 锁定当前 iOS/macOS/Android 信任的两级证书链和服务端叶子指纹。
public class TlsDeploymentCertificateIntegrationTest {
    private static final String DEFAULT_KEY_STORE =
            "/Users/hljy/.net-server/tls-strict-20260810/net-server.p12";
    private static final String EXPECTED_LEAF_SHA256 =
            "AC25C5012DC32C0A3731FE9B1F8284E3A4A87F0144380024C5509A05A8817373";

    @Test
    public void deployedPkcs12ServesPinnedTwoCertificateChain() throws Exception {
        Path keyStorePath = Paths.get(environmentOrDefault(
                "NET_SERVER_TLS_KEYSTORE",
                DEFAULT_KEY_STORE));
        assertTrue("部署 PKCS12 不存在: " + keyStorePath, Files.isRegularFile(keyStorePath));
        char[] password = environmentOrDefault(
                "NET_SERVER_TLS_KEYSTORE_PASSWORD",
                "").toCharArray();
        ExecutorService serverExecutor = Executors.newSingleThreadExecutor();
        try {
            Certificate[] storedChain = loadPrivateKeyChain(keyStorePath, password);
            assertStrictTwoCertificateChain(storedChain);
            assertEquals(
                    EXPECTED_LEAF_SHA256,
                    sha256((X509Certificate) storedChain[0]));

            SSLContext serverContext = new TlsContextFactory().create(keyStorePath, password);
            SSLContext clientContext = clientContext((X509Certificate) storedChain[1]);
            try (SSLServerSocket serverSocket = (SSLServerSocket) serverContext
                    .getServerSocketFactory()
                    .createServerSocket()) {
                serverSocket.setEnabledProtocols(new String[]{"TLSv1.2"});
                serverSocket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
                Future<?> serverHandshake = serverExecutor.submit(() -> {
                    try (SSLSocket accepted = (SSLSocket) serverSocket.accept()) {
                        accepted.setEnabledProtocols(new String[]{"TLSv1.2"});
                        accepted.startHandshake();
                    } catch (Exception exception) {
                        throw new AssertionError("服务端 TLS 握手失败", exception);
                    }
                });

                try (SSLSocket client = (SSLSocket) clientContext.getSocketFactory()
                        .createSocket("127.0.0.1", serverSocket.getLocalPort())) {
                    client.setEnabledProtocols(new String[]{"TLSv1.2"});
                    client.startHandshake();

                    assertEquals("TLSv1.2", client.getSession().getProtocol());
                    Certificate[] peerChain = client.getSession().getPeerCertificates();
                    assertStrictTwoCertificateChain(peerChain);
                    assertEquals(
                            EXPECTED_LEAF_SHA256,
                            sha256((X509Certificate) peerChain[0]));
                }
                serverHandshake.get(3, TimeUnit.SECONDS);
            }
        } finally {
            Arrays.fill(password, '\0');
            serverExecutor.shutdownNow();
        }
    }

    private static Certificate[] loadPrivateKeyChain(
            Path keyStorePath,
            char[] password) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(keyStorePath)) {
            keyStore.load(input, password);
        }
        Enumeration<String> aliases = keyStore.aliases();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            if (keyStore.entryInstanceOf(alias, KeyStore.PrivateKeyEntry.class)) {
                return keyStore.getCertificateChain(alias);
            }
        }
        throw new AssertionError("部署 PKCS12 中没有 PrivateKeyEntry");
    }

    private static void assertStrictTwoCertificateChain(Certificate[] chain) throws Exception {
        assertEquals("服务端必须发送叶子证书和 CA 两级链", 2, chain.length);
        X509Certificate leaf = (X509Certificate) chain[0];
        X509Certificate ca = (X509Certificate) chain[1];
        assertEquals(ca.getSubjectX500Principal(), leaf.getIssuerX500Principal());
        assertEquals(ca.getSubjectX500Principal(), ca.getIssuerX500Principal());
        assertEquals(-1, leaf.getBasicConstraints());
        assertTrue("第二张证书不是 CA", ca.getBasicConstraints() >= 0);
        leaf.verify(ca.getPublicKey());
        ca.verify(ca.getPublicKey());
    }

    private static SSLContext clientContext(X509Certificate caCertificate) throws Exception {
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        trustStore.setCertificateEntry("chat-storage-local-ca", caCertificate);
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trustStore);
        SSLContext context = SSLContext.getInstance("TLSv1.2");
        context.init(null, trustManagers.getTrustManagers(), new SecureRandom());
        return context;
    }

    private static String sha256(X509Certificate certificate) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        StringBuilder value = new StringBuilder(digest.length * 2);
        for (byte item : digest) {
            value.append(String.format(Locale.ROOT, "%02X", item & 0xFF));
        }
        return value.toString();
    }

    private static String environmentOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null ? defaultValue : value;
    }
}
