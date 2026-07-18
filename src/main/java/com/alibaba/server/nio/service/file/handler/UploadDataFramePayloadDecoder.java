package com.alibaba.server.nio.service.file.handler;

import com.alibaba.server.nio.model.file.FileUploadFrame;

import java.nio.ByteBuffer;
import java.util.Arrays;

final class UploadDataFramePayloadDecoder {

    private static final int OFFSET_BYTES = Long.BYTES;

    private UploadDataFramePayloadDecoder() {
    }

    static byte[] decode(FileUploadFrame frame, long expectedOffset) {
        byte[] payload = frame.getData();
        if (payload == null || payload.length == 0) {
            return new byte[0];
        }
        if (!frame.hasOffset()) {
            return payload;
        }
        if (payload.length < OFFSET_BYTES) {
            throw new IllegalArgumentException("上传数据帧缺少8字节偏移量");
        }

        long frameOffset = ByteBuffer.wrap(payload, 0, OFFSET_BYTES).getLong();
        if (frameOffset != expectedOffset) {
            throw new IllegalArgumentException(
                    "上传数据帧偏移不一致: expected=" + expectedOffset + ", actual=" + frameOffset);
        }
        return Arrays.copyOfRange(payload, OFFSET_BYTES, payload.length);
    }
}
