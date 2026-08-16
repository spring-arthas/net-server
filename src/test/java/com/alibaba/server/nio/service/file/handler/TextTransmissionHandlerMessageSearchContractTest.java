package com.alibaba.server.nio.service.file.handler;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TextTransmissionHandlerMessageSearchContractTest {

    @Test
    public void chatSearchForwardsOptionalFriendIdAndDoesNotLogKeywordContent() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/alibaba/server/nio/service/file/handler/TextTransmissionHandler.java")),
                StandardCharsets.UTF_8);
        int start = source.indexOf("private void handleChatMessageSearch");
        int end = source.indexOf("private String mergeReaction", start);
        String handler = source.substring(start, end);

        assertTrue(handler.contains("friendId = request.getInteger(\"friendId\")"));
        assertTrue(handler.contains(".searchMessagesPage(userId.intValue(), friendId, keyword, limit)"));
        assertTrue(handler.contains("keywordLength={}"));
        assertFalse(handler.contains("keyword={},"));
        assertFalse(handler.contains("JSON.toJSONString(frame)"));
        assertTrue(handler.contains("payloadLength={}"));
    }
}
