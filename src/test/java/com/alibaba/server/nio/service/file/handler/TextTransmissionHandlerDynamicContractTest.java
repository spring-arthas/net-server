package com.alibaba.server.nio.service.file.handler;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

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

    private static void assertDispatch(String source, String frame, String handlerCall) {
        assertTrue(source.contains("case " + frame + ":"));
        assertTrue(source.contains(handlerCall));
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }
}
