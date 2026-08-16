package com.alibaba.server.nio.tls;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// [修改] 锁定自动生成证书的两级链、IP SAN 和 TLS 1.2 握手能力。
public class TlsDeploymentCertificateIntegrationTest {
    private static final int IP_ADDRESS_SAN_TYPE = 7;

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void automaticallyProvisionedPkcs12ServesStrictTwoCertificateChain() throws Exception {
        Path keyStorePath = temporaryFolder.getRoot().toPath().resolve("tls/net-server.p12");
        InetAddress publicAddress = InetAddress.getByName("192.168.0.101");
        char[] password = new char[0];
        new TlsKeyStoreProvisioner().provision(
                keyStorePath,
                password,
                publicAddress,
                true);
        assertTrue(Files.isRegularFile(keyStorePath));
        ExecutorService serverExecutor = Executors.newSingleThreadExecutor();
        try {
            Certificate[] storedChain = loadPrivateKeyChain(keyStorePath, password);
            assertStrictTwoCertificateChain(storedChain);
            assertIpSubjectAlternativeName((X509Certificate) storedChain[0], publicAddress);

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
                    assertIpSubjectAlternativeName(
                            (X509Certificate) peerChain[0],
                            publicAddress);
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

    private static void assertIpSubjectAlternativeName(
            X509Certificate certificate,
            InetAddress expectedAddress) throws Exception {
        Collection<List<?>> names = certificate.getSubjectAlternativeNames();
        assertTrue("服务端证书缺少 SAN", names != null);
        for (List<?> name : names) {
            if (name.size() >= 2
                    && Integer.valueOf(IP_ADDRESS_SAN_TYPE).equals(name.get(0))
                    && expectedAddress.equals(InetAddress.getByName(String.valueOf(name.get(1))))) {
                return;
            }
        }
        throw new AssertionError("服务端证书不包含当前 IP SAN: " + expectedAddress.getHostAddress());
    }
}
