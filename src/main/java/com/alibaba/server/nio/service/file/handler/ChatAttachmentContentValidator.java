package com.alibaba.server.nio.service.file.handler;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.server.common.YesOrNoEnum;
import com.alibaba.server.nio.repository.file.service.dto.FileDto;
import java.util.Locale;
import java.util.function.Function;
import org.apache.commons.lang.StringUtils;

final class ChatAttachmentContentValidator {
    private static final int MAX_MIXED_ATTACHMENT_COUNT = 9;

    private ChatAttachmentContentValidator() {
    }

    static String validate(String content,
            String msgType,
            Integer senderId,
            Function<Long, FileDto> fileLookup) {
        String normalizedType = StringUtils.defaultString(msgType, "TEXT").trim().toUpperCase(Locale.ROOT);
        if (!"IMAGE".equals(normalizedType) && !"MIXED".equals(normalizedType)) {
            return content;
        }
        if (StringUtils.isBlank(content)) {
            throw new IllegalArgumentException("附件消息内容不能为空");
        }
        if (senderId == null) {
            throw new IllegalArgumentException("发送方不能为空");
        }
        if (fileLookup == null) {
            throw new IllegalArgumentException("文件查询器不能为空");
        }

        JSONObject root;
        try {
            root = JSON.parseObject(content);
        } catch (Exception e) {
            throw new IllegalArgumentException("附件消息JSON格式错误", e);
        }
        if (root == null) {
            throw new IllegalArgumentException("附件消息内容不能为空");
        }

        if ("IMAGE".equals(normalizedType)) {
            validateImage(root, senderId, fileLookup);
            return content;
        }

        if (!"mixed".equalsIgnoreCase(root.getString("kind"))) {
            throw new IllegalArgumentException("图文消息类型错误");
        }
        JSONArray attachments = root.getJSONArray("attachments");
        if (attachments == null || attachments.isEmpty() || attachments.size() > MAX_MIXED_ATTACHMENT_COUNT) {
            throw new IllegalArgumentException("混合消息附件数量错误");
        }
        for (int i = 0; i < attachments.size(); i++) {
            JSONObject attachment = attachments.getJSONObject(i);
            if (attachment == null) {
                throw new IllegalArgumentException("混合消息附件格式错误");
            }
            validateAttachment(attachment, senderId, fileLookup);
        }
        return content;
    }

    private static void validateAttachment(JSONObject attachment,
            Integer senderId,
            Function<Long, FileDto> fileLookup) {
        String kind = StringUtils.defaultString(attachment.getString("kind"))
                .trim()
                .toLowerCase(Locale.ROOT);
        if ("image".equals(kind)) {
            validateImage(attachment, senderId, fileLookup);
            return;
        }
        if ("file".equals(kind)) {
            validateRequiredFile(
                    "fileId",
                    attachment.getLong("fileId"),
                    senderId,
                    attachment.getString("fileName"),
                    attachment.getLong("fileSize"),
                    fileLookup);
            return;
        }
        throw new IllegalArgumentException("附件类型错误");
    }

    private static void validateImage(JSONObject attachment,
            Integer senderId,
            Function<Long, FileDto> fileLookup) {
        if (!"image".equalsIgnoreCase(attachment.getString("kind"))) {
            throw new IllegalArgumentException("图片附件类型错误");
        }
        validateRequiredFile(
                "fileId",
                attachment.getLong("fileId"),
                senderId,
                attachment.getString("fileName"),
                attachment.getLong("fileSize"),
                fileLookup);
        validateOptionalFile(
                "thumbnailFileId",
                attachment.getLong("thumbnailFileId"),
                senderId,
                attachment.getLong("thumbnailFileSize"),
                fileLookup);
        validateOptionalFile(
                "previewFileId",
                attachment.getLong("previewFileId"),
                senderId,
                attachment.getLong("previewFileSize"),
                fileLookup);
    }

    private static void validateRequiredFile(String field,
            Long fileId,
            Integer senderId,
            String expectedFileName,
            Long expectedFileSize,
            Function<Long, FileDto> fileLookup) {
        if (fileId == null || fileId <= 0) {
            throw validationError("ATTACHMENT_INVALID_ID", field, fileId, field + "无效");
        }
        FileDto file = requireFile(field, fileId, senderId, fileLookup);
        if (StringUtils.isNotBlank(expectedFileName) && !expectedFileName.equals(file.getFileName())) {
            throw validationError("ATTACHMENT_NAME_MISMATCH", field, fileId, field + "文件名不匹配");
        }
        validateSize(field, fileId, expectedFileSize, file);
    }

    private static void validateOptionalFile(String field,
            Long fileId,
            Integer senderId,
            Long expectedFileSize,
            Function<Long, FileDto> fileLookup) {
        if (fileId == null) {
            return;
        }
        if (fileId <= 0) {
            throw validationError("ATTACHMENT_INVALID_ID", field, fileId, field + "无效");
        }
        FileDto file = requireFile(field, fileId, senderId, fileLookup);
        validateSize(field, fileId, expectedFileSize, file);
    }

    private static FileDto requireFile(String field,
            Long fileId,
            Integer senderId,
            Function<Long, FileDto> fileLookup) {
        FileDto file = fileLookup.apply(fileId);
        if (file == null || file.getId() == null || !fileId.equals(file.getId())) {
            throw validationError("ATTACHMENT_NOT_FOUND", field, fileId, field + "不存在");
        }
        if (!senderId.equals(file.getUserId())) {
            throw validationError("ATTACHMENT_OWNER_MISMATCH", field, fileId, field + "不属于发送方");
        }
        if (YesOrNoEnum.Y.name().equals(file.getDel())
                || YesOrNoEnum.N.name().equals(file.getIsExist())
                || !YesOrNoEnum.Y.name().equals(file.getIsFile())) {
            throw validationError("ATTACHMENT_UNAVAILABLE", field, fileId, field + "不可用");
        }
        return file;
    }

    private static void validateSize(String field, Long fileId, Long expectedFileSize, FileDto file) {
        if (expectedFileSize == null) {
            return;
        }
        if (file.getFileSize() == null || !expectedFileSize.equals(file.getFileSize())) {
            throw validationError("ATTACHMENT_SIZE_MISMATCH", field, fileId, field + "大小不匹配");
        }
    }

    private static ChatAttachmentValidationException validationError(
            String errorCode,
            String field,
            Long fileId,
            String message) {
        return new ChatAttachmentValidationException(errorCode, field, fileId, message);
    }
}

final class ChatAttachmentValidationException extends IllegalArgumentException {
    private final String errorCode;
    private final String attachmentField;
    private final Long fileId;

    ChatAttachmentValidationException(String errorCode, String attachmentField, Long fileId, String message) {
        super(message);
        this.errorCode = errorCode;
        this.attachmentField = attachmentField;
        this.fileId = fileId;
    }

    String getErrorCode() {
        return errorCode;
    }

    String getAttachmentField() {
        return attachmentField;
    }

    Long getFileId() {
        return fileId;
    }
}
