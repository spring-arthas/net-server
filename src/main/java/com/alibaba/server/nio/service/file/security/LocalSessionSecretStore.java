package com.alibaba.server.nio.service.file.security;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;

final class LocalSessionSecretStore {
    private static final int SECRET_BYTES = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String SECRET_DIRECTORY = ".net-server";
    private static final String SECRET_FILE = "user-session-token.secret";

    private LocalSessionSecretStore() {
    }

    static String loadOrCreateDefault() {
        String userHome = System.getProperty("user.home");
        if (userHome == null || userHome.trim().isEmpty()) {
            throw new IllegalStateException("Can not resolve user.home for the local session-token secret");
        }
        return loadOrCreate(Paths.get(userHome, SECRET_DIRECTORY, SECRET_FILE));
    }

    static String loadOrCreate(Path secretPath) {
        if (secretPath == null) {
            throw new IllegalArgumentException("secretPath must not be null");
        }

        Path normalizedPath = secretPath.toAbsolutePath().normalize();
        if (Files.exists(normalizedPath)) {
            return readExisting(normalizedPath);
        }

        try {
            Path parent = normalizedPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            String generatedSecret = generateSecret();
            try {
                Files.write(
                        normalizedPath,
                        (generatedSecret + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
            } catch (FileAlreadyExistsException concurrentCreation) {
                return readExisting(normalizedPath);
            }
            restrictToCurrentUser(normalizedPath);
            return generatedSecret;
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Can not create the local session-token secret at " + normalizedPath,
                    e);
        }
    }

    private static String readExisting(Path secretPath) {
        try {
            String secret = new String(Files.readAllBytes(secretPath), StandardCharsets.UTF_8).trim();
            if (secret.isEmpty()) {
                throw new IllegalStateException("Local session-token secret file is blank: " + secretPath);
            }
            restrictToCurrentUser(secretPath);
            return secret;
        } catch (IOException e) {
            throw new IllegalStateException("Can not read the local session-token secret at " + secretPath, e);
        }
    }

    private static String generateSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void restrictToCurrentUser(Path secretPath) {
        try {
            Files.setPosixFilePermissions(secretPath, PosixFilePermissions.fromString("rw-------"));
            return;
        } catch (UnsupportedOperationException | IOException | SecurityException ignored) {
            // Fall back to the platform-neutral owner flags below.
        }

        File file = secretPath.toFile();
        file.setReadable(false, false);
        file.setWritable(false, false);
        file.setExecutable(false, false);
        file.setReadable(true, true);
        file.setWritable(true, true);
    }
}
