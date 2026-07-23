package com.alibaba.server.nio.service.file.handler;

import com.alibaba.server.nio.repository.file.service.dto.FileDto;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ChatAttachmentContentValidatorTest {

    @Test
    public void acceptsMixedImageAttachmentWhenEveryReferencedFileBelongsToSender() {
        Map<Long, FileDto> files = new HashMap<>();
        files.put(7478051867767984128L, file(7478051867767984128L, 5, "2025转入.jpg", 3148898L));
        files.put(7478051869793832960L, file(7478051869793832960L, 5, "chat-thumb.jpg", 19270L));
        files.put(7478051871354114048L, file(7478051871354114048L, 5, "chat-preview.jpg", 212489L));
        String content = "{\"attachments\":[{\"fileId\":7478051867767984128,\"fileName\":\"2025转入.jpg\","
                + "\"fileSize\":3148898,\"kind\":\"image\",\"previewFileId\":7478051871354114048,"
                + "\"previewFileSize\":212489,\"thumbnailFileId\":7478051869793832960,"
                + "\"thumbnailFileSize\":19270}],\"kind\":\"mixed\",\"text\":\"\",\"version\":1}";

        String validated = ChatAttachmentContentValidator.validate(
                content, "MIXED", 5, files::get);

        assertEquals(content, validated);
    }

    @Test
    public void acceptsMixedImageAndFileAttachments() {
        Map<Long, FileDto> files = new HashMap<>();
        files.put(101L, file(101L, 5, "photo.png", 1024L));
        files.put(102L, file(102L, 5, "资料.pdf", 2048L));
        String content = "{\"attachments\":["
                + "{\"fileId\":101,\"fileName\":\"photo.png\",\"fileSize\":1024,\"kind\":\"image\"},"
                + "{\"fileId\":102,\"fileName\":\"资料.pdf\",\"fileSize\":2048,\"kind\":\"file\",\"mimeType\":\"application/pdf\"}"
                + "],\"kind\":\"mixed\",\"text\":\"请查收\",\"version\":2}";

        assertEquals(content, ChatAttachmentContentValidator.validate(content, "MIXED", 5, files::get));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMixedFileAttachmentOwnedByAnotherUser() {
        Map<Long, FileDto> files = new HashMap<>();
        files.put(201L, file(201L, 7, "other-user.pdf", 2048L));
        String content = "{\"attachments\":["
                + "{\"fileId\":201,\"fileName\":\"other-user.pdf\",\"fileSize\":2048,\"kind\":\"file\"}"
                + "],\"kind\":\"mixed\",\"text\":\"\",\"version\":2}";

        ChatAttachmentContentValidator.validate(content, "MIXED", 5, files::get);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMixedFileAttachmentWhenSizeDoesNotMatchPayload() {
        Map<Long, FileDto> files = new HashMap<>();
        files.put(202L, file(202L, 5, "资料.pdf", 4096L));
        String content = "{\"attachments\":["
                + "{\"fileId\":202,\"fileName\":\"资料.pdf\",\"fileSize\":2048,\"kind\":\"file\"}"
                + "],\"kind\":\"mixed\",\"text\":\"\",\"version\":2}";

        ChatAttachmentContentValidator.validate(content, "MIXED", 5, files::get);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnsupportedMixedAttachmentKind() {
        Map<Long, FileDto> files = new HashMap<>();
        files.put(203L, file(203L, 5, "voice.aac", 1024L));
        String content = "{\"attachments\":["
                + "{\"fileId\":203,\"fileName\":\"voice.aac\",\"fileSize\":1024,\"kind\":\"audio\"}"
                + "],\"kind\":\"mixed\",\"text\":\"\",\"version\":2}";

        ChatAttachmentContentValidator.validate(content, "MIXED", 5, files::get);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMixedImageAttachmentWhenFileIdDoesNotExist() {
        String content = "{\"attachments\":[{\"fileId\":452,\"fileName\":\"2025转入.jpg\","
                + "\"fileSize\":3148898,\"kind\":\"image\",\"previewFileId\":452,"
                + "\"previewFileSize\":212489,\"thumbnailFileId\":452,"
                + "\"thumbnailFileSize\":19270}],\"kind\":\"mixed\",\"text\":\"\",\"version\":1}";

        ChatAttachmentContentValidator.validate(content, "MIXED", 5, id -> null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsDerivedFileIdWhenItsSizeDoesNotMatchPayload() {
        Map<Long, FileDto> files = new HashMap<>();
        files.put(100L, file(100L, 5, "2025转入.jpg", 3148898L));
        String content = "{\"attachments\":[{\"fileId\":100,\"fileName\":\"2025转入.jpg\","
                + "\"fileSize\":3148898,\"kind\":\"image\",\"thumbnailFileId\":100,"
                + "\"thumbnailFileSize\":19270}],\"kind\":\"mixed\",\"text\":\"\",\"version\":1}";

        ChatAttachmentContentValidator.validate(content, "MIXED", 5, files::get);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsAttachmentOwnedByAnotherUser() {
        Map<Long, FileDto> files = new HashMap<>();
        files.put(900L, file(900L, 7, "other.jpg", 123L));
        String content = "{\"kind\":\"image\",\"fileId\":900,\"fileName\":\"other.jpg\",\"fileSize\":123}";

        ChatAttachmentContentValidator.validate(content, "IMAGE", 5, files::get);
    }

    private static FileDto file(Long id, Integer userId, String fileName, Long fileSize) {
        FileDto file = new FileDto();
        file.setId(id);
        file.setUserId(userId);
        file.setFileName(fileName);
        file.setFileSize(fileSize);
        file.setIsFile("Y");
        file.setIsExist("Y");
        file.setDel("N");
        return file;
    }
}
