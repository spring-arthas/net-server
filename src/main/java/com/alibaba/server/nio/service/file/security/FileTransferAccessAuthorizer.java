package com.alibaba.server.nio.service.file.security;

import com.alibaba.server.common.YesOrNoEnum;
import com.alibaba.server.nio.core.server.BasicServer;
import com.alibaba.server.nio.repository.chat.mapper.UserFriendMessageRepository;
import com.alibaba.server.nio.repository.dynamic.mapper.UserDynamicRepository;
import com.alibaba.server.nio.repository.file.service.dto.FileDto;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;

import java.util.function.BiPredicate;

@Slf4j
public class FileTransferAccessAuthorizer {

    private final BiPredicate<Long, Long> chatAttachmentAccessChecker;
    private final BiPredicate<Long, Long> dynamicMediaAccessChecker;

    /** 使用 Spring 容器中的默认权限查询器。 */
    public FileTransferAccessAuthorizer() {
        this(null, null);
    }

    /**
     * 注入权限查询器，供媒体下载授权和单元测试复用。
     *
     * @param chatAttachmentAccessChecker 聊天附件权限查询器
     * @param dynamicMediaAccessChecker 动态媒体可见性查询器
     */
    public FileTransferAccessAuthorizer(BiPredicate<Long, Long> chatAttachmentAccessChecker,
            BiPredicate<Long, Long> dynamicMediaAccessChecker) {
        this.chatAttachmentAccessChecker = chatAttachmentAccessChecker;
        this.dynamicMediaAccessChecker = dynamicMediaAccessChecker;
    }

    public void requireDownloadAccess(FileDto fileDto, TransferTokenService.ValidationResult identity) {
        if (fileDto == null || fileDto.getId() == null
                || YesOrNoEnum.Y.name().equals(fileDto.getDel())
                || YesOrNoEnum.N.name().equals(fileDto.getIsExist())
                || !YesOrNoEnum.Y.name().equals(fileDto.getIsFile())) {
            throw new SecurityException("文件不存在或已删除");
        }
        if (identity == null || !identity.isValid()) {
            throw new SecurityException("登录凭据无效或已过期");
        }
        if (identity.getUserId() != null && fileDto.getUserId() != null
                && identity.getUserId().longValue() == fileDto.getUserId().longValue()) {
            return;
        }
        if (StringUtils.equals(fileDto.getUserName(), identity.getUserName())) {
            return;
        }
        if (isChatAttachmentAccessible(identity.getUserId(), fileDto.getId())) {
            return;
        }
        if (isDynamicMediaAccessible(identity.getUserId(), fileDto.getId())) {
            return;
        }
        throw new SecurityException("无权下载该文件");
    }

    private boolean isChatAttachmentAccessible(Long userId, Long fileId) {
        if (userId == null || fileId == null) {
            return false;
        }
        if (chatAttachmentAccessChecker != null) {
            return invokeChecker(chatAttachmentAccessChecker, userId, fileId, "聊天附件");
        }
        if (BasicServer.classPathXmlApplicationContext == null) {
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

    private boolean isDynamicMediaAccessible(Long userId, Long fileId) {
        if (userId == null || fileId == null) {
            return false;
        }
        if (dynamicMediaAccessChecker != null) {
            return invokeChecker(dynamicMediaAccessChecker, userId, fileId, "动态媒体");
        }
        if (BasicServer.classPathXmlApplicationContext == null) {
            return false;
        }
        try {
            UserDynamicRepository repository = BasicServer.classPathXmlApplicationContext
                    .getBean(UserDynamicRepository.class);
            return repository.countVisibleMediaReferences(userId, fileId) > 0;
        } catch (Exception e) {
            // [修改] 迁移未执行或查询失败时拒绝访问，不能降级为放行。
            log.warn("查询动态媒体访问权限失败: userId={}, fileId={}, error={}", userId, fileId, e.getMessage());
            return false;
        }
    }

    private boolean invokeChecker(BiPredicate<Long, Long> checker, Long userId, Long fileId, String resourceType) {
        try {
            return checker.test(userId, fileId);
        } catch (RuntimeException e) {
            log.warn("查询{}访问权限失败: userId={}, fileId={}, error={}", resourceType, userId, fileId,
                    e.getMessage());
            return false;
        }
    }
}
