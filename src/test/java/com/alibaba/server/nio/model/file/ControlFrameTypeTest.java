package com.alibaba.server.nio.model.file;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ControlFrameTypeTest {

    @Test
    public void controlFrameCodesRemainCompatibleWithAndroid() {
        assertEquals(0x46, FileUploadFrame.FrameType.USER_SESSION_RESUME_REQ.getCode());
        assertEquals(0x47, FileUploadFrame.FrameType.CONNECTION_HEARTBEAT_REQ.getCode());
        assertEquals(0x48, FileUploadFrame.FrameType.CONNECTION_HEARTBEAT_RESPONSE.getCode());
    }
}
