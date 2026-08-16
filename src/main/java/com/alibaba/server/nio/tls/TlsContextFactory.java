package com.alibaba.server.nio.tls;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Objects;

/**
 * 从 PKCS12 构建 Java 8 服务端 TLS 上下文。
 */
public final class TlsContextFactory {

    /**
     * 加载服务端私钥和证书链。
     *
     * @param keyStorePath PKCS12 路径
     * @param password PKCS12 密码
     * @return TLS 1.2 上下文
     */
    public SSLContext create(Path keyStorePath, char[] password) {
        Objects.requireNonNull(keyStorePath, "keyStorePath");
        char[] privatePassword = password == null ? new char[0] : password.clone();
        try (InputStream input = Files.newInputStream(keyStorePath)) {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(input, privatePassword);
            requirePrivateKeyEntry(keyStore);
            KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(
                    KeyManagerFactory.getDefaultAlgorithm());
            keyManagers.init(keyStore, privatePassword);
            SSLContext context = SSLContext.getInstance("TLSv1.2");
            context.init(keyManagers.getKeyManagers(), null, new SecureRandom());
            return context;
        } catch (GeneralSecurityException | IOException exception) {
            // [修改] 对外只说明 PKCS12 加载失败，禁止把密码或私钥细节写入异常消息。
            throw new TlsGatewayStartupException("加载 TLS PKCS12 失败", exception);
        } finally {
            Arrays.fill(privatePassword, '\0');
        }
    }

    private void requirePrivateKeyEntry(KeyStore keyStore) throws GeneralSecurityException {
        Enumeration<String> aliases = keyStore.aliases();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            java.security.cert.Certificate[] certificateChain =
                    keyStore.getCertificateChain(alias);
            // [修改] SecretKeyEntry 也属于 key entry，TLS 服务端必须同时具备私钥和证书链。
            if (keyStore.entryInstanceOf(alias, KeyStore.PrivateKeyEntry.class)
                    && certificateChain != null
                    && certificateChain.length > 0) {
                return;
            }
        }
        throw new TlsGatewayStartupException("TLS PKCS12 中没有服务端私钥");
    }
}
