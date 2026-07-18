package com.alibaba.server.nio.service.file.handler;

import com.alibaba.fastjson.JSONObject;
import com.alibaba.server.nio.model.file.FileUploadContext;
import com.alibaba.server.nio.model.file.FileUploadFrame;
import com.alibaba.server.nio.service.file.adaptive.UploadBackpressureDecision;
import org.junit.Test;

import java.nio.ByteBuffer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class UploadDataFrameProtocolTest {

    @Test
    public void shouldStripBigEndianOffsetPrefixBeforeWritingFileBytes() {
        long offset = 8_396_800L;
        byte[] fileBytes = new byte[] { 0x11, 0x22, 0x33 };
        ByteBuffer payload = ByteBuffer.allocate(Long.BYTES + fileBytes.length);
        payload.putLong(offset);
        payload.put(fileBytes);

        FileUploadFrame frame = new FileUploadFrame();
        frame.setFlags((byte) (FileUploadFrame.FLAG_HAS_OFFSET | FileUploadFrame.FLAG_NEED_ACK));
        frame.setData(payload.array());

        assertTrue(frame.hasOffset());
        assertTrue(frame.needAck());
        assertArrayEquals(fileBytes, UploadDataFramePayloadDecoder.decode(frame, offset));
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectDataFrameWhoseOffsetDoesNotMatchServerPosition() {
        ByteBuffer payload = ByteBuffer.allocate(Long.BYTES + 1);
        payload.putLong(100L);
        payload.put((byte) 0x01);

        FileUploadFrame frame = new FileUploadFrame();
        frame.setFlags(FileUploadFrame.FLAG_HAS_OFFSET);
        frame.setData(payload.array());

        UploadDataFramePayloadDecoder.decode(frame, 99L);
    }

    @Test
    public void progressAckShouldContainTaskIdAndConfirmedOffset() {
        FileUploadContext uploadContext = new FileUploadContext();
        uploadContext.setRequestTaskId("client-task-id");

        JSONObject payload = UploadAckPayloadBuilder.build(
                uploadContext,
                null,
                "progress",
                null,
                12_591_104L);

        assertEquals("client-task-id", payload.getString("taskId"));
        assertEquals("progress", payload.getString("status"));
        assertEquals(Long.valueOf(12_591_104L), payload.getLong("uploadedSize"));
    }

    @Test
    public void progressAckShouldContainAdaptiveBackpressureFields() {
        FileUploadContext uploadContext = new FileUploadContext();
        uploadContext.setRequestTaskId("client-task-id");
        UploadBackpressureDecision decision = new UploadBackpressureDecision(
                "slow_down", 65_536, 1_048_576, 42L, 120L);

        JSONObject payload = UploadAckPayloadBuilder.build(
                uploadContext,
                null,
                "progress",
                null,
                1_048_576L,
                decision);

        assertEquals("slow_down", payload.getString("serverState"));
        assertEquals(Integer.valueOf(65_536), payload.getInteger("recommendedChunkSize"));
        assertEquals(Integer.valueOf(1_048_576), payload.getInteger("recommendedAckWindow"));
        assertEquals(Long.valueOf(42L), payload.getLong("serverWriteMillis"));
        assertEquals(Long.valueOf(120L), payload.getLong("retryAfterMs"));
    }
}
