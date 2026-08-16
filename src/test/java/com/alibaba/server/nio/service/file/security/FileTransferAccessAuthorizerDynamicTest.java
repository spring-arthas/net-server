package com.alibaba.server.nio.service.file.security;

import com.alibaba.server.nio.repository.file.service.dto.FileDto;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class FileTransferAccessAuthorizerDynamicTest {

    @Test
    public void visibleDynamicReferenceAllowsFriendDownload() {
        FileTransferAccessAuthorizer authorizer = new FileTransferAccessAuthorizer(
                (userId, fileId) -> false,
                (userId, fileId) -> true);
        TransferTokenService tokenService = new TransferTokenService("test-transfer-secret", 60L);
        TransferTokenService.ValidationResult identity = tokenService.validateToken(
                tokenService.generateToken(9L, "friend"));

        authorizer.requireDownloadAccess(file(77L, "owner"), identity);

        assertEquals(Long.valueOf(9L), identity.getUserId());
    }

    private static FileDto file(Long id, String owner) {
        FileDto file = new FileDto();
        file.setId(id);
        file.setUserName(owner);
        file.setDel("N");
        file.setIsExist("Y");
        file.setIsFile("Y");
        return file;
    }
}
