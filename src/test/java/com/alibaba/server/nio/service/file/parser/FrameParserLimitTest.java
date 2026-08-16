package com.alibaba.server.nio.service.file.parser;

import com.alibaba.server.nio.model.file.FileDownloadFrame;
import com.alibaba.server.nio.model.file.FileUploadFrame;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class FrameParserLimitTest {

    private static final int MAX_FRAME_DATA_SIZE = 10 * 1024 * 1024;

    @Test
    public void uploadParserRejectsOversizedHeaderAndResynchronizes() {
        // [修改] 声明超过实际缓冲能力的帧后，解析器必须跳过坏头并继续读取下一帧。
        byte[] bytes = concatenate(
                frameHeader(FileUploadFrame.FrameType.DATA_FRAME.getCode(), MAX_FRAME_DATA_SIZE + 1),
                frameHeader(FileUploadFrame.FrameType.END_FRAME.getCode(), 0));

        List<FileUploadFrame> frames = new FrameUploadParser().parse(bytes);

        assertEquals(1, frames.size());
        assertEquals(FileUploadFrame.FrameType.END_FRAME, frames.get(0).getType());
    }

    @Test
    public void downloadParserRejectsOversizedHeaderAndResynchronizes() {
        // [修改] 下载帧和上传帧使用同一 10MB 数据上限，避免协议声明与缓冲区不一致。
        byte[] bytes = concatenate(
                frameHeader(FileDownloadFrame.FrameType.DATA_FRAME.getCode(), MAX_FRAME_DATA_SIZE + 1),
                frameHeader(FileDownloadFrame.FrameType.END_FRAME.getCode(), 0));

        List<FileDownloadFrame> frames = new FrameDownloadParser().parse(bytes);

        assertEquals(1, frames.size());
        assertEquals(FileDownloadFrame.FrameType.END_FRAME, frames.get(0).getType());
    }

    private byte[] frameHeader(int type, int dataLength) {
        return ByteBuffer.allocate(8)
                .put((byte) 0xFA)
                .put((byte) 0xCE)
                .put((byte) type)
                .put((byte) 0)
                .putInt(dataLength)
                .array();
    }

    private byte[] concatenate(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
