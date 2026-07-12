package com.alibaba.server.nio.service.file.security;

import com.alibaba.server.common.YesOrNoEnum;
import com.alibaba.server.nio.core.server.BasicServer;
import com.alibaba.server.nio.repository.chat.mapper.UserFriendMessageRepository;
import com.alibaba.server.nio.repository.file.service.dto.FileDto;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;

@Slf4j
public class FileTransferAccessAuthorizer {

    public void requireDownloadAccess(FileDto fileDto, TransferTokenService.ValidationResult identity) {
        if (fileDto == null || fileDto.getId() == null
                || YesOrNoEnum.Y.name().equals(fileDto.getDel())
                || YesOrNoEnum.N.name().equals(fileDto.getIsExist())
                || !YesOrNoEnum.Y.name().equals(fileDto.getIsFile())) {
            throw new SecurityException("文件不存在或已删除");
        }
        if (StringUtils.equals(fileDto.getUserName(), identity.getUserName())) {
            return;
        }
        if (isChatAttachmentAccessible(identity.getUserId(), fileDto.getId())) {
            return;
        }
        throw new SecurityException("无权下载该文件");
    }

    private boolean isChatAttachmentAccessible(Long userId, Long fileId) {
        if (userId == null || fileId == null || BasicServer.classPathXmlApplicationContext == null) {
            return false;
        }
        try {
            UserFriendMessageRepository repository = BasicServer.classPathXmlApplicationContext
                    .getBean(UserFriendMessageRepository.class);
            return repository.countAttachmentReferencesForUser(userId, fileId) > 0;
        } catch (Exception e) {
            log.warn("查询聊天附件访问权限失败: userId={}, fileId={}, error={}", userId, fileId, e.getMessage());
            return false;
        }
    }
}
