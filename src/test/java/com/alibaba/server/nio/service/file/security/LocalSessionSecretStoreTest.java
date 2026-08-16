package com.alibaba.server.nio.service.file.security;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class LocalSessionSecretStoreTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void missingSecretFileIsGeneratedAndReused() throws Exception {
        Path secretPath = temporaryFolder.getRoot().toPath()
                .resolve("nested")
                .resolve("user-session-token.secret");

        String first = LocalSessionSecretStore.loadOrCreate(secretPath);
        String second = LocalSessionSecretStore.loadOrCreate(secretPath);

        assertFalse(first.trim().isEmpty());
        assertEquals(first, second);
        assertEquals(first, new String(Files.readAllBytes(secretPath), StandardCharsets.UTF_8).trim());
    }

    @Test
    public void existingSecretIsTrimmedAndReused() throws Exception {
        Path secretPath = temporaryFolder.newFile("existing.secret").toPath();
        Files.write(secretPath, "  existing-local-secret  \n".getBytes(StandardCharsets.UTF_8));

        String secret = LocalSessionSecretStore.loadOrCreate(secretPath);

        assertEquals("existing-local-secret", secret);
    }

    @Test
    public void generatedSecretUsesOwnerOnlyPosixPermissions() throws Exception {
        Assume.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path secretPath = temporaryFolder.getRoot().toPath().resolve("owner-only.secret");

        LocalSessionSecretStore.loadOrCreate(secretPath);

        assertEquals(
                PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(secretPath));
    }
}
