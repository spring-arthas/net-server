package com.alibaba.server.nio.service.resumable;

import com.alibaba.server.nio.model.file.FileUploadFrame;
import com.alibaba.server.nio.model.resumable.ResumableFrame;
import com.alibaba.server.nio.service.file.parser.FrameParser;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 断点续传帧解析器
 * 使用状态机模型处理 TCP 粘包/半包问题
 * 
 * 协议格式：
 * +--------+--------+--------+--------+--------+
 * | Magic  | Type   | Flags  | Length | Data   |
 * | 2字节  | 1字节  | 1字节  | 4字节  | N字节  |
 * +--------+--------+--------+--------+--------+
 */
public class ResumableFrameParser {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ResumableFrameParser.class);

    /**
     * 解析状态枚举
     */
    public enum ParseState {
        WAIT_MAGIC,     // 等待魔数
        PARSE_HEADER,   // 解析帧头
        PARSE_DATA,     // 解析数据
        FRAME_COMPLETE  // 帧解析完成
    }

    /**
     * 重置解析器状态
     */
    public void reset() {
        state = ParseState.WAIT_MAGIC;
        buffer.reset();
        position = 0;
        currentFrame = null;
        currentDataLength = 0;
        // log.debug("解析器已重置");
    }

    private static final byte[] MAGIC = ResumableFrame.MAGIC;
    private static final int HEADER_LENGTH = ResumableFrame.HEADER_LENGTH;

    private ParseState state = ParseState.WAIT_MAGIC;
    private ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private int position = 0;

    private ResumableFrame currentFrame = null;
    private int currentDataLength = 0;

    /**
     * 渐进式解析字节流
     */
    public List<ResumableFrame> parse(byte[] data) {
        List<ResumableFrame> completeFrames = new ArrayList<>();

        if (data == null || data.length == 0) {
            return completeFrames;
        }

        // 将新数据追加到缓存
        buffer.write(data, 0, data.length);

        // 状态机驱动解析
        boolean continueParsing = true;
        while (continueParsing) {
            continueParsing = processState(completeFrames);
        }

        // 压缩缓存
        compactBuffer();

        return completeFrames;
    }

    private boolean processState(List<ResumableFrame> completeFrames) {
        byte[] buf = buffer.toByteArray();
        int available = buf.length - position;

        switch (state) {
            case WAIT_MAGIC:
                return handleWaitMagic(buf, available);

            case PARSE_HEADER:
                return handleParseHeader(buf, available);

            case PARSE_DATA:
                return handleParseData(buf, available);

            case FRAME_COMPLETE:
                return handleFrameComplete(completeFrames);

            default:
                log.error("未知解析状态: {}", state);
                return false;
        }
    }

    /**
     * 等待魔数状态处理 (滑动窗口查找)
     */
    private boolean handleWaitMagic(byte[] buf, int available) {
        if (available < 2) {
            return false; // 需要更多数据
        }

        // 查找魔数
        while (position <= buf.length - 2) {
            if (buf[position] == MAGIC[0] && buf[position + 1] == MAGIC[1]) {
                // 找到魔数，进入头部解析状态
                state = ParseState.PARSE_HEADER;
                // log.debug("找到魔数，位置: {}", position);
                return true;
            }
            // 没找到，跳过一个字节继续找
            position++;
        }

        return false; // 未找到魔数，需要更多数据
    }

    /**
     * 解析帧头状态处理
     */
    private boolean handleParseHeader(byte[] buf, int available) {
        if (available < HEADER_LENGTH) {
            return false;
        }

        int headerStart = position + 2; // 跳过 Magic

        // 解析类型
        byte typeCode = buf[headerStart];
        ResumableFrame.FrameType frameType = ResumableFrame.FrameType.fromCode(typeCode);
        if (frameType == null) {
            log.warn("无效的帧类型: 0x{}", String.format("%02X", typeCode));
            position += 2; // 跳过这个魔数，继续寻找
            state = ResumableFrameParser.ParseState.WAIT_MAGIC;
            return true;
        }
        // 解析标志
        byte flags = buf[headerStart + 1];
        // 解析长度
        int length = ByteBuffer.wrap(buf, headerStart + 2, 4).getInt();

        // 校验长度
        if (length < 0 || length > 100 * 1024 * 1024) { // 100MB limit
            log.warn("无效的数据长度: {}, 丢弃当前魔数", length);
            position += 2; // 丢弃当前魔数，重新寻找
            state = ParseState.WAIT_MAGIC;
            return true;
        }

        currentFrame = new ResumableFrame();
        currentFrame.setType(frameType);
        currentFrame.setFlags(flags);
        currentFrame.setLength(length);
        currentDataLength = length;

        position += HEADER_LENGTH;

        if (length > 0) {
            state = ParseState.PARSE_DATA;
        } else {
            state = ParseState.FRAME_COMPLETE;
        }

        return true;
    }

    /**
     *
     *
     * 解析数据状态处理
     */
    private boolean handleParseData(byte[] buf, int available) {
        if (available < currentDataLength) {
            return false;
        }

        byte[] data = Arrays.copyOfRange(buf, position, position + currentDataLength);
        currentFrame.setData(data);

        position += currentDataLength;
        state = ParseState.FRAME_COMPLETE;
        return true;
    }

    /**
     * 帧完成状态处理
     */
    private boolean handleFrameComplete(List<ResumableFrame> completeFrames) {
        completeFrames.add(currentFrame);
        
        currentFrame = null;
        currentDataLength = 0;
        state = ParseState.WAIT_MAGIC;

        byte[] buf = buffer.toByteArray();
        return buf.length - position >= 2;
    }

    /**
     * 压缩缓存
     */
    private void compactBuffer() {
        if (position > 0) {
            byte[] buf = buffer.toByteArray();
            if (position >= buf.length) {
                buffer.reset();
                position = 0;
            } else if (position > buf.length / 2) {
                // 超过一半已消费，进行压缩
                byte[] remaining = Arrays.copyOfRange(buf, position, buf.length);
                buffer.reset();
                buffer.write(remaining, 0, remaining.length);
                position = 0;
            }
        }
    }
}
