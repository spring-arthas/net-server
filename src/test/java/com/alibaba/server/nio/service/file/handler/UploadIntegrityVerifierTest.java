package com.alibaba.server.nio.service.file.handler;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UploadIntegrityVerifierTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void shouldAcceptMatchingSizeAndMd5IgnoringCase() throws Exception {
        File file = temporaryFolder.newFile("complete.bin");
        Files.write(file.toPath(), "adaptive-upload".getBytes(StandardCharsets.UTF_8));

        UploadIntegrityVerifier.VerificationResult result = UploadIntegrityVerifier.verify(
                file.toPath(), file.length(), "E0910C3BEF935122903814874929951A");

        assertTrue(result.isValid());
        assertEquals("e0910c3bef935122903814874929951a", result.getActualMd5());
    }

    @Test
    public void shouldRejectMismatchedSizeBeforeHashing() throws Exception {
        File file = temporaryFolder.newFile("short.bin");
        Files.write(file.toPath(), "short".getBytes(StandardCharsets.UTF_8));

        UploadIntegrityVerifier.VerificationResult result = UploadIntegrityVerifier.verify(
                file.toPath(), file.length() + 1L,
                "4F09DA2F7CDA2C1E59B3F0A955C4B50D");

        assertFalse(result.isValid());
        assertTrue(result.getMessage().contains("大小校验失败"));
    }

    @Test
    public void shouldRejectMismatchedMd5() throws Exception {
        File file = temporaryFolder.newFile("bad-md5.bin");
        Files.write(file.toPath(), "content".getBytes(StandardCharsets.UTF_8));

        UploadIntegrityVerifier.VerificationResult result = UploadIntegrityVerifier.verify(
                file.toPath(), file.length(), "00000000000000000000000000000000");

        assertFalse(result.isValid());
        assertTrue(result.getMessage().contains("MD5 校验失败"));
    }

    @Test
    public void shouldRejectMissingFileOrMd5() throws Exception {
        UploadIntegrityVerifier.VerificationResult missingFile = UploadIntegrityVerifier.verify(
                temporaryFolder.getRoot().toPath().resolve("missing.bin"), 1L,
                "00000000000000000000000000000000");
        File file = temporaryFolder.newFile("missing-md5.bin");
        UploadIntegrityVerifier.VerificationResult missingMd5 = UploadIntegrityVerifier.verify(
                file.toPath(), 0L, null);

        assertTrue(missingFile.getMessage().contains("不存在"));
        assertTrue(missingMd5.getMessage().contains("缺少 MD5"));
    }
}
