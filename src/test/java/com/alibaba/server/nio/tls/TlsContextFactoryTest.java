package com.alibaba.server.nio.tls;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLContext;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

// [修改] 验证 Java 8 能加载现有部署格式的 PKCS12，且错误不泄漏密码。
public class TlsContextFactoryTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void loadsPrivateKeyEntry() throws Exception {
        Path keyStore = TlsTestKeyStore.create(temporaryFolder, "changeit");

        SSLContext context = new TlsContextFactory().create(
                keyStore,
                "changeit".toCharArray());

        assertEquals("TLSv1.2", context.getProtocol());
    }

    @Test
    public void rejectsWrongPasswordWithoutLeakingIt() throws Exception {
        Path keyStore = TlsTestKeyStore.create(temporaryFolder, "changeit");

        try {
            new TlsContextFactory().create(keyStore, "private-value".toCharArray());
            fail("expected TlsGatewayStartupException");
        } catch (TlsGatewayStartupException exception) {
            assertTrue(exception.getMessage(), exception.getMessage().contains("PKCS12"));
            assertFalse(exception.getMessage().contains("private-value"));
        }
    }

    @Test
    public void rejectsKeyStoreWithoutPrivateKey() throws Exception {
        Path keyStorePath = temporaryFolder.newFile("empty.p12").toPath();
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        try (OutputStream output = Files.newOutputStream(keyStorePath)) {
            keyStore.store(output, "changeit".toCharArray());
        }

        try {
            new TlsContextFactory().create(keyStorePath, "changeit".toCharArray());
            fail("expected TlsGatewayStartupException");
        } catch (TlsGatewayStartupException exception) {
            assertTrue(exception.getMessage(), exception.getMessage().contains("私钥"));
        }
    }

    @Test
    public void rejectsSecretKeyEntryWithoutServerPrivateKey() throws Exception {
        Path keyStorePath = temporaryFolder.newFile("secret-key-only.p12").toPath();
        char[] password = "changeit".toCharArray();
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, password);
        // [修改] AES SecretKeyEntry 也是 isKeyEntry，但不能提供 TLS 服务端证书。
        keyStore.setEntry(
                "aes-secret",
                new KeyStore.SecretKeyEntry(new SecretKeySpec(new byte[16], "AES")),
                new KeyStore.PasswordProtection(password));
        try (OutputStream output = Files.newOutputStream(keyStorePath)) {
            keyStore.store(output, password);
        }

        try {
            new TlsContextFactory().create(keyStorePath, password);
            fail("expected TlsGatewayStartupException");
        } catch (TlsGatewayStartupException exception) {
            assertTrue(exception.getMessage(), exception.getMessage().contains("私钥"));
        }
    }
}
