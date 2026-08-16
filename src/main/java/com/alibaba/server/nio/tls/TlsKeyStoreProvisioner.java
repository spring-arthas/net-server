package com.alibaba.server.nio.tls;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/**
 * 为本机自动创建并维护 TLS Gateway 使用的 PKCS12。
 */
@Slf4j
public final class TlsKeyStoreProvisioner {
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";
    private static final String KEY_ALGORITHM = "RSA";
    private static final int KEY_SIZE = 2_048;
    private static final int CA_VALIDITY_DAYS = 3_650;
    private static final int SERVER_VALIDITY_DAYS = 397;
    private static final int CLOCK_SKEW_MINUTES = 5;
    private static final int LOCAL_CA_PASSWORD_BYTES = 32;

    private static final String SERVER_ALIAS = "net-server";
    private static final String CA_ALIAS = "chat-storage-local-ca";
    private static final String MANAGED_BY_KEY = "managed.by";
    private static final String MANAGED_BY_VALUE = "net-server-tls-provisioner";
    private static final String FORMAT_VERSION_KEY = "format.version";
    private static final String FORMAT_VERSION_VALUE = "1";
    private static final String PUBLIC_IP_KEY = "public.ip";
    private static final String GENERATED_AT_KEY = "generated.at";
    private static final String KEYSTORE_SHA256_KEY = "keystore.sha256";
    private static final String PENDING_KEYSTORE_SHA256_KEY = "pending.keystore.sha256";
    private static final String PENDING_PUBLIC_IP_KEY = "pending.public.ip";
    private static final String CA_SHA256_KEY = "ca.certificate.sha256";

    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
            PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> PRIVATE_FILE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> PUBLIC_CERTIFICATE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-r--r--");

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 根据当前 TLS 配置确保 PKCS12 已就绪。
     *
     * @param config TLS Gateway 配置
     * @return 可加载的 PKCS12 路径
     */
    public Path provision(TlsGatewayConfig config) {
        Objects.requireNonNull(config, "config");
        char[] password = config.copyKeyStorePassword();
        try {
            return provision(
                    config.getKeyStorePath(),
                    password,
                    config.getBindAddress(),
                    config.isKeyStoreAutoCreate());
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    Path provision(
            Path keyStorePath,
            char[] password,
            InetAddress publicAddress,
            boolean autoCreate) {
        Objects.requireNonNull(keyStorePath, "keyStorePath");
        Objects.requireNonNull(publicAddress, "publicAddress");
        Path normalizedPath = keyStorePath.toAbsolutePath().normalize();
        char[] privatePassword = password == null ? new char[0] : password.clone();
        try {
            if (!autoCreate) {
                requireExistingKeyStore(normalizedPath);
                return normalizedPath;
            }
            if (Files.isSymbolicLink(normalizedPath)) {
                if (Files.isRegularFile(normalizedPath)) {
                    log.info("使用符号链接指向的自定义 TLS PKCS12，不自动覆盖: {}", normalizedPath);
                    return normalizedPath;
                }
                throw new TlsGatewayConfigurationException(
                        "NET_SERVER_TLS_KEYSTORE 是无效符号链接: " + normalizedPath);
            }
            requireFileOrMissing(normalizedPath);

            ArtifactPaths artifacts = ArtifactPaths.from(normalizedPath);
            // 元数据和文件摘要必须同时匹配，
            // 避免把用户替换后的自定义证书误判为自动证书。
            if (Files.isRegularFile(normalizedPath)
                    && !isAutoManaged(normalizedPath, readMetadata(artifacts.metadataPath))) {
                log.info("使用已有自定义 TLS PKCS12，不自动覆盖: {}", normalizedPath);
                return normalizedPath;
            }

            Files.createDirectories(artifacts.parentDirectory);
            setPermissions(artifacts.parentDirectory, DIRECTORY_PERMISSIONS);
            synchronized (TlsKeyStoreProvisioner.class) {
                try (FileChannel lockChannel = FileChannel.open(
                        artifacts.lockPath,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE);
                        FileLock ignored = lockChannel.lock()) {
                    setPermissions(artifacts.lockPath, PRIVATE_FILE_PERMISSIONS);
                    return provisionWithLock(
                            normalizedPath,
                            privatePassword,
                            publicAddress,
                            artifacts);
                }
            }
        } catch (IOException | GeneralSecurityException | OperatorCreationException exception) {
            throw new TlsGatewayConfigurationException(
                    "自动创建 TLS PKCS12 失败: " + normalizedPath,
                    exception);
        } finally {
            Arrays.fill(privatePassword, '\0');
        }
    }

    private Path provisionWithLock(
            Path keyStorePath,
            char[] password,
            InetAddress publicAddress,
            ArtifactPaths artifacts)
            throws IOException, GeneralSecurityException, OperatorCreationException {
        requireFileOrMissing(keyStorePath);
        Properties existingMetadata = readMetadata(artifacts.metadataPath);
        String existingHash = Files.isRegularFile(keyStorePath) ? sha256(keyStorePath) : null;
        if (Files.isRegularFile(keyStorePath)
                && !isAutoManaged(existingMetadata, existingHash)) {
            log.info("使用已有自定义 TLS PKCS12，不自动覆盖: {}", keyStorePath);
            return keyStorePath;
        }

        if (Files.isRegularFile(keyStorePath)
                && isKeyStoreUsable(keyStorePath, password, publicAddress)) {
            finalizePendingMetadataIfNecessary(
                    existingMetadata,
                    existingHash,
                    publicAddress,
                    artifacts.metadataPath);
            log.info("复用本机 TLS PKCS12: path={}, address={}",
                    keyStorePath, certificateAddress(publicAddress));
            return keyStorePath;
        }

        LocalCertificateAuthority certificateAuthority = loadOrCreateCertificateAuthority(artifacts);
        ServerIdentity serverIdentity = createServerIdentity(
                publicAddress,
                certificateAuthority.privateKey,
                certificateAuthority.certificate);
        byte[] keyStoreBytes = createServerKeyStore(
                serverIdentity.privateKey,
                serverIdentity.certificate,
                certificateAuthority.certificate,
                password);
        String newHash = sha256(keyStoreBytes);
        Properties finalMetadata = metadata(
                publicAddress,
                newHash,
                certificateAuthority.certificate);
        Properties pendingMetadata = pendingMetadata(finalMetadata, existingHash, newHash, publicAddress);

        // 先记录旧/新摘要，再原子替换 PKCS12，
        // 进程中断后仍能判断文件所有权并安全恢复。
        writePropertiesAtomically(
                artifacts.metadataPath,
                pendingMetadata,
                PRIVATE_FILE_PERMISSIONS);
        writeAtomically(keyStorePath, keyStoreBytes, PRIVATE_FILE_PERMISSIONS);
        writePropertiesAtomically(
                artifacts.metadataPath,
                finalMetadata,
                PRIVATE_FILE_PERMISSIONS);

        log.info("TLS PKCS12 已自动创建或更新: path={}, address={}, ca={}",
                keyStorePath,
                certificateAddress(publicAddress),
                artifacts.caPemPath);
        return keyStorePath;
    }

    private ServerIdentity createServerIdentity(
            InetAddress publicAddress,
            PrivateKey certificateAuthorityKey,
            X509Certificate certificateAuthority)
            throws IOException, GeneralSecurityException, OperatorCreationException {
        KeyPair serverKeyPair = generateKeyPair();
        Instant now = Instant.now();
        // 直接复用 CA 证书中的 ASN.1 Distinguished Name，避免先转成字符串后
        // RDN 顺序发生变化，导致 JDK 8 的 PKCS12 实现判定证书链无效。
        X500Name issuer = X500Name.getInstance(
                certificateAuthority.getSubjectX500Principal().getEncoded());
        String publicIp = certificateAddress(publicAddress);
        X500Name subject = new X500Name("CN=" + publicIp + ",O=net-server");
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                issuer,
                serialNumber(),
                java.util.Date.from(now.minus(CLOCK_SKEW_MINUTES, ChronoUnit.MINUTES)),
                java.util.Date.from(now.plus(SERVER_VALIDITY_DAYS, ChronoUnit.DAYS)),
                subject,
                serverKeyPair.getPublic());
        JcaX509ExtensionUtils extensionUtils = new JcaX509ExtensionUtils();
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        builder.addExtension(
                Extension.keyUsage,
                true,
                new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
        builder.addExtension(
                Extension.extendedKeyUsage,
                false,
                new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
        builder.addExtension(
                Extension.subjectKeyIdentifier,
                false,
                extensionUtils.createSubjectKeyIdentifier(serverKeyPair.getPublic()));
        builder.addExtension(
                Extension.authorityKeyIdentifier,
                false,
                extensionUtils.createAuthorityKeyIdentifier(certificateAuthority));
        builder.addExtension(
                Extension.subjectAlternativeName,
                false,
                serverAlternativeNames(publicIp));

        ContentSigner signer = new JcaContentSignerBuilder(SIGNATURE_ALGORITHM)
                .build(certificateAuthorityKey);
        X509Certificate certificate = convertCertificate(builder.build(signer));
        certificate.checkValidity();
        certificate.verify(certificateAuthority.getPublicKey());
        return new ServerIdentity(serverKeyPair.getPrivate(), certificate);
    }

    private LocalCertificateAuthority loadOrCreateCertificateAuthority(ArtifactPaths artifacts)
            throws IOException, GeneralSecurityException, OperatorCreationException {
        if (Files.isRegularFile(artifacts.caKeyStorePath)
                && Files.isRegularFile(artifacts.caPasswordPath)) {
            try {
                LocalCertificateAuthority existing = loadCertificateAuthority(artifacts);
                exportCertificateAuthority(existing.certificate, artifacts);
                return existing;
            } catch (IOException | GeneralSecurityException exception) {
                log.warn("本机 TLS CA 无法继续使用，将重新创建: path={}, reason={}",
                        artifacts.caKeyStorePath, exception.getMessage());
            }
        }
        return createCertificateAuthority(artifacts);
    }

    private LocalCertificateAuthority loadCertificateAuthority(ArtifactPaths artifacts)
            throws IOException, GeneralSecurityException {
        char[] password = readLocalCaPassword(artifacts.caPasswordPath);
        try (InputStream input = Files.newInputStream(artifacts.caKeyStorePath)) {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(input, password);
            Key key = keyStore.getKey(CA_ALIAS, password);
            Certificate certificate = keyStore.getCertificate(CA_ALIAS);
            if (!(key instanceof PrivateKey) || !(certificate instanceof X509Certificate)) {
                throw new GeneralSecurityException("本机 TLS CA 条目不完整");
            }
            X509Certificate x509Certificate = (X509Certificate) certificate;
            x509Certificate.checkValidity();
            if (x509Certificate.getNotAfter().toInstant().isBefore(
                    Instant.now().plus(SERVER_VALIDITY_DAYS + 1L, ChronoUnit.DAYS))) {
                throw new GeneralSecurityException("本机 TLS CA 剩余有效期不足");
            }
            if (x509Certificate.getBasicConstraints() < 0) {
                throw new GeneralSecurityException("本机 TLS CA 证书缺少 CA 约束");
            }
            verifyKeyPair((PrivateKey) key, x509Certificate);
            return new LocalCertificateAuthority((PrivateKey) key, x509Certificate);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private LocalCertificateAuthority createCertificateAuthority(ArtifactPaths artifacts)
            throws IOException, GeneralSecurityException, OperatorCreationException {
        KeyPair keyPair = generateKeyPair();
        Instant now = Instant.now();
        X500Name subject = new X500Name("CN=net-server Local CA,O=net-server");
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject,
                serialNumber(),
                java.util.Date.from(now.minus(CLOCK_SKEW_MINUTES, ChronoUnit.MINUTES)),
                java.util.Date.from(now.plus(CA_VALIDITY_DAYS, ChronoUnit.DAYS)),
                subject,
                keyPair.getPublic());
        JcaX509ExtensionUtils extensionUtils = new JcaX509ExtensionUtils();
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(0));
        builder.addExtension(
                Extension.keyUsage,
                true,
                new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
        builder.addExtension(
                Extension.subjectKeyIdentifier,
                false,
                extensionUtils.createSubjectKeyIdentifier(keyPair.getPublic()));
        builder.addExtension(
                Extension.authorityKeyIdentifier,
                false,
                extensionUtils.createAuthorityKeyIdentifier(keyPair.getPublic()));
        ContentSigner signer = new JcaContentSignerBuilder(SIGNATURE_ALGORITHM)
                .build(keyPair.getPrivate());
        X509Certificate certificate = convertCertificate(builder.build(signer));
        certificate.checkValidity();
        certificate.verify(keyPair.getPublic());

        char[] password = newLocalCaPassword();
        try {
            writeAtomically(
                    artifacts.caPasswordPath,
                    new String(password).getBytes(StandardCharsets.US_ASCII),
                    PRIVATE_FILE_PERMISSIONS);
            writeAtomically(
                    artifacts.caKeyStorePath,
                    createCaKeyStore(keyPair.getPrivate(), certificate, password),
                    PRIVATE_FILE_PERMISSIONS);
        } finally {
            Arrays.fill(password, '\0');
        }
        exportCertificateAuthority(certificate, artifacts);
        return new LocalCertificateAuthority(keyPair.getPrivate(), certificate);
    }

    private void exportCertificateAuthority(
            X509Certificate certificate,
            ArtifactPaths artifacts) throws IOException, GeneralSecurityException {
        byte[] der = certificate.getEncoded();
        String base64 = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(der);
        String pem = "-----BEGIN CERTIFICATE-----\n"
                + base64
                + "\n-----END CERTIFICATE-----\n";
        writeAtomically(
                artifacts.caPemPath,
                pem.getBytes(StandardCharsets.US_ASCII),
                PUBLIC_CERTIFICATE_PERMISSIONS);
        writeAtomically(
                artifacts.caDerPath,
                der,
                PUBLIC_CERTIFICATE_PERMISSIONS);
    }

    private byte[] createServerKeyStore(
            PrivateKey privateKey,
            X509Certificate serverCertificate,
            X509Certificate certificateAuthority,
            char[] password) throws IOException, GeneralSecurityException {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, password);
        keyStore.setKeyEntry(
                SERVER_ALIAS,
                privateKey,
                password,
                new Certificate[]{serverCertificate, certificateAuthority});
        return storeKeyStore(keyStore, password);
    }

    private byte[] createCaKeyStore(
            PrivateKey privateKey,
            X509Certificate certificate,
            char[] password) throws IOException, GeneralSecurityException {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, password);
        keyStore.setKeyEntry(CA_ALIAS, privateKey, password, new Certificate[]{certificate});
        return storeKeyStore(keyStore, password);
    }

    private byte[] storeKeyStore(KeyStore keyStore, char[] password)
            throws IOException, GeneralSecurityException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        keyStore.store(output, password);
        return output.toByteArray();
    }

    private boolean isKeyStoreUsable(
            Path keyStorePath,
            char[] password,
            InetAddress publicAddress) {
        try (InputStream input = Files.newInputStream(keyStorePath)) {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(input, password);
            Enumeration<String> aliases = keyStore.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                if (!keyStore.entryInstanceOf(alias, KeyStore.PrivateKeyEntry.class)) {
                    continue;
                }
                Certificate certificate = keyStore.getCertificate(alias);
                if (certificate instanceof X509Certificate) {
                    X509Certificate x509Certificate = (X509Certificate) certificate;
                    x509Certificate.checkValidity();
                    return containsIpSubjectAlternativeName(x509Certificate, publicAddress);
                }
            }
            return false;
        } catch (IOException | GeneralSecurityException exception) {
            log.debug("自动管理的 TLS PKCS12 需要重新生成: path={}, reason={}",
                    keyStorePath, exception.getMessage());
            return false;
        }
    }

    private boolean containsIpSubjectAlternativeName(
            X509Certificate certificate,
            InetAddress expectedAddress) throws CertificateParsingException {
        Collection<List<?>> subjectAlternativeNames = certificate.getSubjectAlternativeNames();
        if (subjectAlternativeNames == null) {
            return false;
        }
        for (List<?> subjectAlternativeName : subjectAlternativeNames) {
            if (subjectAlternativeName.size() < 2
                    || !Integer.valueOf(GeneralName.iPAddress).equals(subjectAlternativeName.get(0))) {
                continue;
            }
            Object value = subjectAlternativeName.get(1);
            if (value instanceof String) {
                try {
                    if (expectedAddress.equals(InetAddress.getByName((String) value))) {
                        return true;
                    }
                } catch (java.net.UnknownHostException exception) {
                    log.debug("忽略无法解析的证书 IP SAN: {}", value);
                }
            }
        }
        return false;
    }

    private Properties metadata(
            InetAddress publicAddress,
            String keyStoreHash,
            X509Certificate certificateAuthority) throws GeneralSecurityException {
        Properties metadata = new Properties();
        metadata.setProperty(MANAGED_BY_KEY, MANAGED_BY_VALUE);
        metadata.setProperty(FORMAT_VERSION_KEY, FORMAT_VERSION_VALUE);
        metadata.setProperty(PUBLIC_IP_KEY, certificateAddress(publicAddress));
        metadata.setProperty(GENERATED_AT_KEY, Instant.now().toString());
        metadata.setProperty(KEYSTORE_SHA256_KEY, keyStoreHash);
        metadata.setProperty(CA_SHA256_KEY, sha256(certificateAuthority.getEncoded()));
        return metadata;
    }

    private Properties pendingMetadata(
            Properties finalMetadata,
            String existingHash,
            String newHash,
            InetAddress publicAddress) {
        Properties pending = copyProperties(finalMetadata);
        if (StringUtils.isBlank(existingHash)) {
            pending.remove(KEYSTORE_SHA256_KEY);
        } else {
            pending.setProperty(KEYSTORE_SHA256_KEY, existingHash);
        }
        pending.setProperty(PENDING_KEYSTORE_SHA256_KEY, newHash);
        pending.setProperty(PENDING_PUBLIC_IP_KEY, certificateAddress(publicAddress));
        return pending;
    }

    private void finalizePendingMetadataIfNecessary(
            Properties metadata,
            String currentHash,
            InetAddress publicAddress,
            Path metadataPath) throws IOException {
        if (StringUtils.isBlank(metadata.getProperty(PENDING_KEYSTORE_SHA256_KEY))) {
            return;
        }
        Properties finalized = copyProperties(metadata);
        finalized.setProperty(KEYSTORE_SHA256_KEY, currentHash);
        finalized.setProperty(PUBLIC_IP_KEY, certificateAddress(publicAddress));
        finalized.remove(PENDING_KEYSTORE_SHA256_KEY);
        finalized.remove(PENDING_PUBLIC_IP_KEY);
        writePropertiesAtomically(metadataPath, finalized, PRIVATE_FILE_PERMISSIONS);
    }

    private Properties copyProperties(Properties source) {
        Properties copy = new Properties();
        for (String name : source.stringPropertyNames()) {
            copy.setProperty(name, source.getProperty(name));
        }
        return copy;
    }

    private Properties readMetadata(Path metadataPath) throws IOException {
        Properties metadata = new Properties();
        if (!Files.isRegularFile(metadataPath)) {
            return metadata;
        }
        try (InputStream input = Files.newInputStream(metadataPath)) {
            metadata.load(input);
        }
        return metadata;
    }

    private boolean isAutoManaged(Path keyStorePath, Properties metadata)
            throws IOException, GeneralSecurityException {
        if (!Files.isRegularFile(keyStorePath)) {
            return false;
        }
        return isAutoManaged(metadata, sha256(keyStorePath));
    }

    private boolean isAutoManaged(Properties metadata, String currentHash) {
        if (!MANAGED_BY_VALUE.equals(metadata.getProperty(MANAGED_BY_KEY))
                || !FORMAT_VERSION_VALUE.equals(metadata.getProperty(FORMAT_VERSION_KEY))
                || StringUtils.isBlank(currentHash)) {
            return false;
        }
        return currentHash.equalsIgnoreCase(metadata.getProperty(KEYSTORE_SHA256_KEY))
                || currentHash.equalsIgnoreCase(metadata.getProperty(PENDING_KEYSTORE_SHA256_KEY));
    }

    private void requireExistingKeyStore(Path keyStorePath) {
        if (!Files.isRegularFile(keyStorePath)) {
            throw new TlsGatewayConfigurationException(
                    "NET_SERVER_TLS_KEYSTORE 文件不存在: " + keyStorePath);
        }
    }

    private void requireFileOrMissing(Path keyStorePath) {
        if (Files.exists(keyStorePath) && !Files.isRegularFile(keyStorePath)) {
            throw new TlsGatewayConfigurationException(
                    "NET_SERVER_TLS_KEYSTORE 不是普通文件: " + keyStorePath);
        }
    }

    private void writePropertiesAtomically(
            Path target,
            Properties properties,
            Set<PosixFilePermission> permissions) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        properties.store(output, "net-server auto-managed TLS metadata");
        writeAtomically(target, output.toByteArray(), permissions);
    }

    private void writeAtomically(
            Path target,
            byte[] content,
            Set<PosixFilePermission> permissions) throws IOException {
        Path parent = target.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            throw new IOException("TLS 文件缺少父目录: " + target);
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".net-server-tls-", ".tmp");
        try {
            Files.write(
                    temporary,
                    content,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            setPermissions(temporary, permissions);
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicMoveException) {
                if (!Files.exists(temporary)) {
                    throw atomicMoveException;
                }
                try {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException fallbackMoveException) {
                    fallbackMoveException.addSuppressed(atomicMoveException);
                    throw fallbackMoveException;
                }
            }
            setPermissions(target, permissions);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupException) {
                log.warn("清理 TLS 临时文件失败: path={}, reason={}",
                        temporary, cleanupException.getMessage());
            }
        }
    }

    private void setPermissions(Path path, Set<PosixFilePermission> permissions) throws IOException {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException exception) {
            log.debug("当前文件系统不支持 POSIX 权限，跳过权限设置: {}", path);
        }
    }

    private char[] newLocalCaPassword() {
        byte[] randomBytes = new byte[LOCAL_CA_PASSWORD_BYTES];
        secureRandom.nextBytes(randomBytes);
        try {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(randomBytes)
                    .toCharArray();
        } finally {
            Arrays.fill(randomBytes, (byte) 0);
        }
    }

    private char[] readLocalCaPassword(Path passwordPath) throws IOException, GeneralSecurityException {
        String value = new String(Files.readAllBytes(passwordPath), StandardCharsets.US_ASCII).trim();
        if (StringUtils.isBlank(value)) {
            throw new GeneralSecurityException("本机 TLS CA 密码文件为空");
        }
        return value.toCharArray();
    }

    private KeyPair generateKeyPair() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance(KEY_ALGORITHM);
        generator.initialize(KEY_SIZE, secureRandom);
        return generator.generateKeyPair();
    }

    private void verifyKeyPair(PrivateKey privateKey, X509Certificate certificate)
            throws GeneralSecurityException {
        byte[] challenge = new byte[32];
        secureRandom.nextBytes(challenge);
        try {
            Signature signer = Signature.getInstance(SIGNATURE_ALGORITHM);
            signer.initSign(privateKey, secureRandom);
            signer.update(challenge);
            byte[] signature = signer.sign();

            Signature verifier = Signature.getInstance(SIGNATURE_ALGORITHM);
            verifier.initVerify(certificate.getPublicKey());
            verifier.update(challenge);
            if (!verifier.verify(signature)) {
                throw new GeneralSecurityException("本机 TLS CA 私钥与证书不匹配");
            }
        } finally {
            Arrays.fill(challenge, (byte) 0);
        }
    }

    private X509Certificate convertCertificate(X509CertificateHolder holder)
            throws GeneralSecurityException {
        return new JcaX509CertificateConverter()
                .getCertificate(holder);
    }

    private GeneralNames serverAlternativeNames(String publicIp) {
        List<GeneralName> names = new ArrayList<>();
        names.add(new GeneralName(GeneralName.iPAddress, publicIp));
        if (!"127.0.0.1".equals(publicIp)) {
            names.add(new GeneralName(GeneralName.iPAddress, "127.0.0.1"));
        }
        names.add(new GeneralName(GeneralName.dNSName, "localhost"));
        return new GeneralNames(names.toArray(new GeneralName[0]));
    }

    private BigInteger serialNumber() {
        BigInteger serial;
        do {
            serial = new BigInteger(160, secureRandom);
        } while (serial.signum() <= 0);
        return serial;
    }

    private String sha256(Path path) throws IOException, GeneralSecurityException {
        return sha256(Files.readAllBytes(path));
    }

    private String sha256(byte[] content) throws GeneralSecurityException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(content);
        StringBuilder value = new StringBuilder(hash.length * 2);
        for (byte current : hash) {
            value.append(String.format(Locale.ROOT, "%02x", current & 0xff));
        }
        return value.toString();
    }

    private String certificateAddress(InetAddress address) {
        String value = address.getHostAddress();
        int scopeSeparator = value.indexOf('%');
        return scopeSeparator < 0 ? value : value.substring(0, scopeSeparator);
    }

    static Path caCertificatePath(Path keyStorePath) {
        return ArtifactPaths.from(keyStorePath.toAbsolutePath().normalize()).caPemPath;
    }

    static Path caDerCertificatePath(Path keyStorePath) {
        return ArtifactPaths.from(keyStorePath.toAbsolutePath().normalize()).caDerPath;
    }

    static Path metadataPath(Path keyStorePath) {
        return ArtifactPaths.from(keyStorePath.toAbsolutePath().normalize()).metadataPath;
    }

    private static final class LocalCertificateAuthority {
        private final PrivateKey privateKey;
        private final X509Certificate certificate;

        private LocalCertificateAuthority(PrivateKey privateKey, X509Certificate certificate) {
            this.privateKey = privateKey;
            this.certificate = certificate;
        }
    }

    private static final class ServerIdentity {
        private final PrivateKey privateKey;
        private final X509Certificate certificate;

        private ServerIdentity(PrivateKey privateKey, X509Certificate certificate) {
            this.privateKey = privateKey;
            this.certificate = certificate;
        }
    }

    private static final class ArtifactPaths {
        private final Path parentDirectory;
        private final Path caKeyStorePath;
        private final Path caPasswordPath;
        private final Path caPemPath;
        private final Path caDerPath;
        private final Path metadataPath;
        private final Path lockPath;

        private ArtifactPaths(
                Path parentDirectory,
                Path caKeyStorePath,
                Path caPasswordPath,
                Path caPemPath,
                Path caDerPath,
                Path metadataPath,
                Path lockPath) {
            this.parentDirectory = parentDirectory;
            this.caKeyStorePath = caKeyStorePath;
            this.caPasswordPath = caPasswordPath;
            this.caPemPath = caPemPath;
            this.caDerPath = caDerPath;
            this.metadataPath = metadataPath;
            this.lockPath = lockPath;
        }

        private static ArtifactPaths from(Path keyStorePath) {
            Path parent = keyStorePath.getParent();
            if (parent == null) {
                throw new TlsGatewayConfigurationException(
                        "NET_SERVER_TLS_KEYSTORE 缺少父目录: " + keyStorePath);
            }
            String fileName = keyStorePath.getFileName().toString();
            int extension = fileName.toLowerCase(Locale.ROOT).endsWith(".p12")
                    ? fileName.length() - 4
                    : fileName.length();
            String prefix = fileName.substring(0, extension);
            if (StringUtils.isBlank(prefix)) {
                throw new TlsGatewayConfigurationException(
                        "NET_SERVER_TLS_KEYSTORE 文件名无效: " + keyStorePath);
            }
            String publicCaPrefix = "net-server".equalsIgnoreCase(prefix)
                    ? "chat-storage-local-ca"
                    : prefix + "-local-ca";
            return new ArtifactPaths(
                    parent,
                    parent.resolve(prefix + "-local-ca.p12"),
                    parent.resolve(prefix + "-local-ca.password"),
                    parent.resolve(publicCaPrefix + ".crt"),
                    parent.resolve(publicCaPrefix + ".der"),
                    parent.resolve(prefix + "-tls-auto.properties"),
                    parent.resolve("." + prefix + "-tls-auto.lock"));
        }
    }
}
