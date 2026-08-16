package com.alibaba.server.nio.model.file;

import org.junit.Assert;
import org.junit.Test;

public class DynamicFrameTypeTest {

    @Test
    public void reservesDynamicProtocolRangeWithoutChangingExistingFrames() {
        Assert.assertEquals(0x60, FileUploadFrame.FrameType.DYNAMIC_CREATE_REQ.getCode());
        Assert.assertEquals(0x61, FileUploadFrame.FrameType.DYNAMIC_CREATE_RESPONSE.getCode());
        Assert.assertEquals(0x62, FileUploadFrame.FrameType.DYNAMIC_TIMELINE_REQ.getCode());
        Assert.assertEquals(0x63, FileUploadFrame.FrameType.DYNAMIC_TIMELINE_RESPONSE.getCode());
        Assert.assertEquals(0x64, FileUploadFrame.FrameType.DYNAMIC_ACTION_REQ.getCode());
        Assert.assertEquals(0x65, FileUploadFrame.FrameType.DYNAMIC_ACTION_RESPONSE.getCode());
        Assert.assertEquals(0x66, FileUploadFrame.FrameType.DYNAMIC_DETAIL_REQ.getCode());
        Assert.assertEquals(0x67, FileUploadFrame.FrameType.DYNAMIC_DETAIL_RESPONSE.getCode());
        Assert.assertEquals(0x68, FileUploadFrame.FrameType.DYNAMIC_DELETE_REQ.getCode());
        Assert.assertEquals(0x69, FileUploadFrame.FrameType.DYNAMIC_DELETE_RESPONSE.getCode());
        Assert.assertEquals(0x5F, FileUploadFrame.FrameType.CHAT_MSG_SEARCH_RESPONSE.getCode());
    }
}
