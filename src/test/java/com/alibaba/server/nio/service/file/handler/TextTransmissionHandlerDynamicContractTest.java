package com.alibaba.server.nio.service.file.handler;

import com.alibaba.server.nio.repository.dynamic.service.dto.DynamicAuthorDTO;
import com.alibaba.server.nio.repository.dynamic.service.dto.DynamicPostDTO;
import com.alibaba.server.nio.repository.dynamic.service.dto.DynamicTimelinePage;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TextTransmissionHandlerDynamicContractTest {

    @Test
    public void dispatchesCompleteAuthenticatedDynamicProtocol() throws Exception {
        String source = read("src/main/java/com/alibaba/server/nio/service/file/handler/TextTransmissionHandler.java");

        assertDispatch(source, "DYNAMIC_CREATE_REQ", "handleDynamicCreate(frame, context)");
        assertDispatch(source, "DYNAMIC_TIMELINE_REQ", "handleDynamicTimeline(frame, context)");
        assertDispatch(source, "DYNAMIC_ACTION_REQ", "handleDynamicAction(frame, context)");
        assertDispatch(source, "DYNAMIC_DETAIL_REQ", "handleDynamicDetail(frame, context)");
        assertDispatch(source, "DYNAMIC_DELETE_REQ", "handleDynamicDelete(frame, context)");
        assertTrue(source.contains("context.getAttribute(\"loggedInUserId\")"));
        assertTrue(source.contains("FrameType.DYNAMIC_CREATE_RESPONSE"));
        assertTrue(source.contains("FrameType.DYNAMIC_TIMELINE_RESPONSE"));
        assertTrue(source.contains("FrameType.DYNAMIC_ACTION_RESPONSE"));
        assertTrue(source.contains("FrameType.DYNAMIC_DETAIL_RESPONSE"));
        assertTrue(source.contains("FrameType.DYNAMIC_DELETE_RESPONSE"));
    }

    @Test
    public void dynamicHandlersNeverLogContentMediaTokensOrCompleteFrames() throws Exception {
        String source = read("src/main/java/com/alibaba/server/nio/service/file/handler/TextTransmissionHandler.java");
        int start = source.indexOf("private UserDynamicService getUserDynamicService()");
        String dynamicSection = source.substring(start);

        assertFalse(dynamicSection.contains("JSON.toJSONString(frame)"));
        assertFalse(dynamicSection.contains("log.info(\"{}\", frame.getDataAsString())"));
        assertFalse(dynamicSection.contains("log.error(\"{}\", frame.getDataAsString())"));
        assertFalse(dynamicSection.contains("imagePaths={}"));
        assertFalse(dynamicSection.contains("content={}"));
        assertFalse(dynamicSection.contains("token={}"));
    }

    @Test
    public void springContextRegistersDynamicService() throws Exception {
        String source = read("src/main/resources/spring/applicationContext.xml");
        assertTrue(source.contains("id=\"userDynamicService\""));
        assertTrue(source.contains("com.alibaba.server.nio.repository.dynamic.service.impl.UserDynamicServiceImpl"));
    }

    @Test
    public void dynamicTimelineConvertsLocalAuthorAvatarToClientReadableBase64() throws Exception {
        Path avatar = Files.createTempFile("dynamic-avatar", ".png");
        try {
            byte[] avatarBytes = new byte[] {1, 2, 3, 4};
            Files.write(avatar, avatarBytes);
            DynamicAuthorDTO author = new DynamicAuthorDTO(7L, "alice", "Alice", avatar.toString());
            DynamicPostDTO post = new DynamicPostDTO();
            post.setAuthor(author);
            DynamicTimelinePage page = new DynamicTimelinePage(
                    Collections.singletonList(post), null, false);

            DynamicAvatarResponseEnricher.enrich(page);

            assertEquals(java.util.Base64.getEncoder().encodeToString(avatarBytes),
                    page.getPosts().get(0).getAuthor().getAvatar());
            assertNotEquals(avatar.toString(), page.getPosts().get(0).getAuthor().getAvatar());
        } finally {
            Files.deleteIfExists(avatar);
        }
    }

    @Test
    public void dynamicHandlersEnrichAvatarForCreateTimelineAndDetailResponses() throws Exception {
        String source = read("src/main/java/com/alibaba/server/nio/service/file/handler/TextTransmissionHandler.java");
        assertEquals(3, occurrences(source, "DynamicAvatarResponseEnricher.enrich(result);"));
    }

    @Test
    public void dynamicAvatarEnricherPreservesClientReadableAvatarFormats() {
        DynamicAuthorDTO author = new DynamicAuthorDTO(7L, "alice", "Alice", "AQID");
        DynamicPostDTO post = new DynamicPostDTO();
        post.setAuthor(author);

        DynamicAvatarResponseEnricher.enrich(new DynamicTimelinePage(
                Collections.singletonList(post), null, false));

        assertEquals("AQID", post.getAuthor().getAvatar());
    }

    private static int occurrences(String source, String value) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(value, offset)) >= 0) {
            count++;
            offset += value.length();
        }
        return count;
    }

    private static void assertDispatch(String source, String frame, String handlerCall) {
        assertTrue(source.contains("case " + frame + ":"));
        assertTrue(source.contains(handlerCall));
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }
}
