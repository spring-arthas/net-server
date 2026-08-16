package com.alibaba.server.nio.model.file;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class FriendPinFrameTypeTest {

    @Test
    public void friendPinFrameCodesRemainCompatibleWithAndroid() {
        assertEquals(0x5C, FileUploadFrame.FrameType.USER_FRIEND_PIN_UPDATE_REQ.getCode());
        assertEquals(0x5D, FileUploadFrame.FrameType.USER_FRIEND_PIN_UPDATE_RESPONSE.getCode());
    }
}
