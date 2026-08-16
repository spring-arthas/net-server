package com.alibaba.server.nio.service.file.handler;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TextTransmissionHandlerFriendPinContractTest {

    @Test
    public void dispatchesAuthenticatedFriendPinSetterAndReturnsCanonicalState() throws Exception {
        String source = source();

        assertTrue(source.contains("case USER_FRIEND_PIN_UPDATE_REQ:"));
        assertTrue(source.contains("handleFriendPinUpdate(frame, context);"));
        assertTrue(source.contains("private void handleFriendPinUpdate("));

        String handler = source.substring(source.indexOf("private void handleFriendPinUpdate("));
        handler = handler.substring(0, handler.indexOf("\n    private void ", 1));
        assertTrue(handler.contains("context.getAttribute(\"loggedInUserId\")"));
        assertTrue(handler.contains("UserAuthFrame.ErrorCode.NOT_LOGGED_IN"));
        assertTrue(handler.contains("request.getLong(\"relationshipId\")"));
        assertTrue(handler.contains("request.getBoolean(\"pinned\")"));
        assertTrue(handler.contains("getFriendshipService()"));
        assertTrue(handler.contains(".updatePinned(userId.intValue(), relationshipId, pinned)"));
        assertTrue(handler.contains("FrameType.USER_FRIEND_PIN_UPDATE_RESPONSE"));
        assertTrue(handler.contains("result.getRelationshipId()"));
        assertTrue(handler.contains("result.isPinned()"));
        assertTrue(handler.contains("result.getPinnedAt().getTime()"));
        assertFalse(handler.contains("request.getInteger(\"userId\")"));
        assertFalse(handler.contains("JSON.toJSONString(frame)"));
    }

    private String source() throws Exception {
        Path sourcePath = Paths.get(
                "src/main/java/com/alibaba/server/nio/service/file/handler/TextTransmissionHandler.java");
        return new String(Files.readAllBytes(sourcePath), StandardCharsets.UTF_8);
    }
}
