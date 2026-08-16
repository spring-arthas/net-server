package com.alibaba.server.nio.tls;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TlsKeyStoreProvisionerTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void createsMissingKeyStoreAndReusableLocalCertificateAuthority() throws IOException {
        Path keyStorePath = temporaryFolder.getRoot().toPath()
                .resolve("tls/net-server.p12");

        new TlsKeyStoreProvisioner().provision(
                keyStorePath,
                new char[0],
                InetAddress.getByName("192.168.0.101"),
                true);

        assertTrue(Files.isRegularFile(keyStorePath));
        assertTrue(Files.isRegularFile(TlsKeyStoreProvisioner.caCertificatePath(keyStorePath)));
        assertTrue(Files.isRegularFile(TlsKeyStoreProvisioner.caDerCertificatePath(keyStorePath)));
        assertTrue(Files.isRegularFile(TlsKeyStoreProvisioner.metadataPath(keyStorePath)));
        assertTrue(new TlsContextFactory().create(keyStorePath, new char[0]) != null);
    }

    @Test
    public void reusesKeyStoreWhenCurrentIpHasNotChanged() throws IOException {
        Path keyStorePath = temporaryFolder.getRoot().toPath().resolve("same-ip/net-server.p12");
        TlsKeyStoreProvisioner provisioner = new TlsKeyStoreProvisioner();
        InetAddress address = InetAddress.getByName("192.168.0.101");

        provisioner.provision(keyStorePath, new char[0], address, true);
        byte[] first = Files.readAllBytes(keyStorePath);
        provisioner.provision(keyStorePath, new char[0], address, true);

        assertArrayEquals(first, Files.readAllBytes(keyStorePath));
    }

    @Test
    public void reissuesServerCertificateButKeepsLocalCaWhenIpChanges() throws IOException {
        Path keyStorePath = temporaryFolder.getRoot().toPath().resolve("ip-change/net-server.p12");
        TlsKeyStoreProvisioner provisioner = new TlsKeyStoreProvisioner();

        provisioner.provision(
                keyStorePath,
                new char[0],
                InetAddress.getByName("192.168.0.101"),
                true);
        byte[] firstKeyStore = Files.readAllBytes(keyStorePath);
        byte[] firstCa = Files.readAllBytes(TlsKeyStoreProvisioner.caDerCertificatePath(keyStorePath));

        provisioner.provision(
                keyStorePath,
                new char[0],
                InetAddress.getByName("192.168.2.106"),
                true);

        assertFalse(java.util.Arrays.equals(firstKeyStore, Files.readAllBytes(keyStorePath)));
        assertArrayEquals(
                firstCa,
                Files.readAllBytes(TlsKeyStoreProvisioner.caDerCertificatePath(keyStorePath)));
        assertTrue(new TlsContextFactory().create(keyStorePath, new char[0]) != null);
    }

    @Test
    public void doesNotOverwriteExistingCustomKeyStore() throws IOException {
        Path keyStorePath = temporaryFolder.newFile("custom.p12").toPath();
        byte[] customContent = new byte[]{1, 2, 3, 4};
        Files.write(keyStorePath, customContent);

        new TlsKeyStoreProvisioner().provision(
                keyStorePath,
                new char[0],
                InetAddress.getByName("192.168.0.101"),
                true);

        assertArrayEquals(customContent, Files.readAllBytes(keyStorePath));
        assertFalse(Files.exists(TlsKeyStoreProvisioner.metadataPath(keyStorePath)));
    }

    @Test
    public void rejectsMissingKeyStoreWhenAutoCreationIsDisabled() throws IOException {
        Path keyStorePath = temporaryFolder.getRoot().toPath().resolve("disabled/net-server.p12");

        try {
            new TlsKeyStoreProvisioner().provision(
                    keyStorePath,
                    new char[0],
                    InetAddress.getByName("192.168.0.101"),
                    false);
            fail("expected TlsGatewayConfigurationException");
        } catch (TlsGatewayConfigurationException exception) {
            assertTrue(exception.getMessage().contains("文件不存在"));
        }
    }
}
