package com.alibaba.server.nio.service.file.parser;

import com.alibaba.server.nio.model.file.FileUploadFrame;
import com.alibaba.server.nio.model.file.FileUploadFrame.FrameType;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 文件上传帧解析器
 * 使用状态机模型处理 TCP 粘包/半包问题
 * 
 * 协议格式：
 * +--------+--------+--------+--------+--------+
 * | Magic | Type | Flags | Length | Data |
 * | 2字节 | 1字节 | 1字节 | 4字节 | N字节 |
 * +--------+--------+--------+--------+--------+
 * 
 * @author YSFY
 */
@Slf4j
public class FileUploadFrameParser {

    /**
     * 解析状态枚举
     */
    public enum ParseState {
        WAIT_MAGIC, // 等待魔数
        PARSE_HEADER, // 解析帧头
        PARSE_DATA, // 解析数据
        FRAME_COMPLETE // 帧解析完成
    }

    /**
     * 魔数
     */
    private static final byte[] MAGIC = FileUploadFrame.MAGIC;

    /**
     * 帧头长度
     */
    private static final int HEADER_LENGTH = FileUploadFrame.HEADER_LENGTH;

    /**
     * 当前解析状态
     */
    private ParseState state = ParseState.WAIT_MAGIC;

    /**
     * 数据缓存
     */
    private ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    /**
     * 当前读取位置
     */
    private int position = 0;

    /**
     * 当前正在解析的帧
     */
    private FileUploadFrame currentFrame = null;

    /**
     * 当前帧的数据长度
     */
    private int currentDataLength = 0;

    /**
     * 渐进式解析字节流
     * 
     * @param data 输入字节数据
     * @return 解析完成的帧列表（可能为空）
     */
    public List<FileUploadFrame> parse(byte[] data) {
        List<FileUploadFrame> completeFrames = new ArrayList<>();

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

    /**
     * 处理当前状态
     * 
     * @param completeFrames 用于收集完成帧的列表
     * @return true=继续解析，false=需要更多数据
     */
    private boolean processState(List<FileUploadFrame> completeFrames) {
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
     * 等待魔数状态处理
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
                log.debug("找到魔数，位置: {}", position);
                return true;
            }
            position++; // 跳过无效字节
        }

        return false; // 未找到魔数，需要更多数据
    }

    /**
     * 解析帧头状态处理
     */
    private boolean handleParseHeader(byte[] buf, int available) {
        // 检查是否有足够的数据解析完整帧头
        if (available < HEADER_LENGTH) {
            return false; // 需要更多数据
        }

        // 跳过魔数（2字节）
        int headerStart = position + 2;

        // 解析帧类型（1字节）
        byte typeCode = buf[headerStart];
        FrameType frameType = FrameType.fromCode(typeCode);
        if (frameType == null) {
            log.warn("无效的帧类型: 0x{}", String.format("%02X", typeCode));
            position += 2; // 跳过这个魔数，继续寻找
            state = ParseState.WAIT_MAGIC;
            return true;
        }

        // 解析标志位（1字节）
        byte flags = buf[headerStart + 1];

        // 解析数据长度（4字节，大端序）
        int dataLength = ByteBuffer.wrap(buf, headerStart + 2, 4).getInt();

        // 验证数据长度合理性
        if (dataLength < 0 || dataLength > 100 * 1024 * 1024) { // 最大100MB
            log.warn("无效的数据长度: {}", dataLength);
            position += 2;
            state = ParseState.WAIT_MAGIC;
            return true;
        }

        // 创建帧对象
        currentFrame = new FileUploadFrame();
        currentFrame.setType(frameType);
        currentFrame.setFlags(flags);
        currentFrame.setDataLength(dataLength);
        currentDataLength = dataLength;

        // 移动位置到数据部分
        position += HEADER_LENGTH;

        // 进入数据解析状态
        if (dataLength > 0) {
            state = ParseState.PARSE_DATA;
        } else {
            state = ParseState.FRAME_COMPLETE;
        }

        log.debug("解析帧头: type={}, flags={}, dataLength={}",
                frameType, flags, dataLength);

        return true;
    }

    /**
     * 解析数据状态处理
     */
    private boolean handleParseData(byte[] buf, int available) {
        // 重新计算可用数据
        available = buf.length - position;

        if (available < currentDataLength) {
            return false; // 需要更多数据
        }

        // 提取数据内容
        byte[] data = Arrays.copyOfRange(buf, position, position + currentDataLength);
        currentFrame.setData(data);

        // 移动位置
        position += currentDataLength;

        // 进入帧完成状态
        state = ParseState.FRAME_COMPLETE;

        log.debug("解析数据: {} 字节", currentDataLength);

        return true;
    }

    /**
     * 帧完成状态处理
     */
    private boolean handleFrameComplete(List<FileUploadFrame> completeFrames) {
        // 添加到完成列表
        completeFrames.add(currentFrame);

        log.debug("帧解析完成: {}", currentFrame);

        // 重置状态，准备解析下一帧
        currentFrame = null;
        currentDataLength = 0;
        state = ParseState.WAIT_MAGIC;

        // 检查是否还有数据可以继续解析
        byte[] buf = buffer.toByteArray();
        return buf.length - position >= 2; // 至少需要2字节才能开始解析下一帧
    }

    /**
     * 压缩缓存，移除已处理的字节
     */
    private void compactBuffer() {
        if (position > 0) {
            byte[] buf = buffer.toByteArray();
            if (position >= buf.length) {
                // 全部消费完，重置缓存
                buffer.reset();
                position = 0;
            } else if (position > buf.length / 2) {
                // 已消费超过一半，进行压缩
                byte[] remaining = Arrays.copyOfRange(buf, position, buf.length);
                buffer.reset();
                buffer.write(remaining, 0, remaining.length);
                position = 0;
                log.debug("压缩缓存: 剩余 {} 字节", remaining.length);
            }
        }
    }

    /**
     * 获取当前缓存中待处理的字节数
     */
    public int getPendingBytesCount() {
        return buffer.size() - position;
    }

    /**
     * 获取当前解析状态
     */
    public ParseState getCurrentState() {
        return state;
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
        log.debug("解析器已重置");
    }
}
