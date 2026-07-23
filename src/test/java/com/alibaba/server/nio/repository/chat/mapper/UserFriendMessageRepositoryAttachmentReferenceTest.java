package com.alibaba.server.nio.repository.chat.mapper;

import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Select;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class UserFriendMessageRepositoryAttachmentReferenceTest {

    @Test
    public void attachmentReferenceQueryIncludesAllChatAttachmentFileIdFields() throws Exception {
        Method method = UserFriendMessageRepository.class
                .getMethod("countAttachmentReferencesForUser", Long.class, Long.class);
        Select select = method.getAnnotation(Select.class);
        String sql = String.join(" ", select.value());

        assertTrue(sql.contains("fileId"));
        assertTrue(sql.contains("previewFileId"));
        assertTrue(sql.contains("thumbnailFileId"));
    }
}
