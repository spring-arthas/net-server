package com.alibaba.server.nio.service.file.parser;

import com.alibaba.server.nio.model.file.FileMessageFrame;
import com.alibaba.server.nio.service.file.util.FileMessageFrameParseUtil;
import com.alibaba.server.util.BasicUtil;
import com.alibaba.server.util.ByteOrderConvert;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 文件帧渐进式解析器
 * 使用状态机模型处理TCP粘包/半包问题，支持任意长度的字节流输入
 * 
 * @author YSFY
 */
@Slf4j
public class FileFrameParser {

    /**
     * 解析状态枚举
     */
    public enum ParseState {
        /** 解析结束帧标志 (1字节) */
        PARSE_END_FRAME,
        /** 解析帧序号 (4字节) */
        PARSE_FRAME_INDEX,
        /** 解析帧类型 (1字节) */
        PARSE_FRAME_TYPE,
        /** 解析文件类型 (1字节) */
        PARSE_FILE_TYPE,
        /** 解析操作类型 (1字节) */
        PARSE_OPERATE_TYPE,
        /** 解析数据长度 (2字节) - 用于UPLOAD/DOWNLOAD/ONLINE_TRANSPORT帧 */
        PARSE_DATA_LENGTH,
        /** 解析文件长度 (8字节) - 仅用于UPLOAD/DOWNLOAD帧 */
        PARSE_FILE_LENGTH,
        /** 解析数据内容 (可变长度) */
        PARSE_DATA_CONTENT,
        /** 解析完成 */
        PARSE_COMPLETE
    }

    /**
     * 解析结果
     */
    @Data
    public static class ParseResult {
        /** 已解析完成的帧列表 */
        private List<FileMessageFrame> completeFrames = new ArrayList<>();
        /** 是否需要更多数据 */
        private boolean needMoreData = false;
    }

    /** 当前解析状态 */
    private ParseState state = ParseState.PARSE_END_FRAME;

    /** 半包缓存Buffer */
    private ByteArrayOutputStream pendingBuffer = new ByteArrayOutputStream();

    /** 当前正在解析的帧 */
    private FileMessageFrame currentFrame = null;

    /** 当前读取位置 */
    private int position = 0;

    /** 需要读取的数据内容长度 */
    private int dataContentLength = 0;

    /**
     * 渐进式解析字节流
     * 
     * @param bytes 输入字节数组
     * @return 解析结果
     */
    public ParseResult parse(byte[] bytes) {
        ParseResult result = new ParseResult();

        if (bytes == null || bytes.length == 0) {
            result.setNeedMoreData(true);
            return result;
        }

        // 将新数据追加到缓存
        pendingBuffer.write(bytes, 0, bytes.length);

        // 状态机驱动解析
        boolean continueparse = true;
        while (continueparse) {
            continueparse = processCurrentState(result);
        }

        // 定期压缩缓存（避免内存累积）
        compactBuffer();

        return result;
    }

    /**
     * 处理当前状态
     * 
     * @param result 解析结果
     * @return true 继续解析，false 需要更多数据
     */
    private boolean processCurrentState(ParseResult result) {
        byte[] buffer = pendingBuffer.toByteArray();
        int available = buffer.length - position;

        switch (state) {
            case PARSE_END_FRAME:
                if (available < 1) {
                    result.setNeedMoreData(true);
                    return false;
                }
                // 初始化新帧
                currentFrame = new FileMessageFrame();
                currentFrame.setEndFrame(buffer[position]);
                position++;
                state = ParseState.PARSE_FRAME_INDEX;
                return true;

            case PARSE_FRAME_INDEX:
                if (available < 4) {
                    result.setNeedMoreData(true);
                    return false;
                }
                byte[] indexBytes = Arrays.copyOfRange(buffer, position, position + 4);
                currentFrame.setFrameIndex(BasicUtil.byteArrayToInt(indexBytes));
                position += 4;
                state = ParseState.PARSE_FRAME_TYPE;
                return true;

            case PARSE_FRAME_TYPE:
                if (available < 1) {
                    result.setNeedMoreData(true);
                    return false;
                }
                // 这里暂存帧类型字节，需要连续读取3个字节后统一解析
                state = ParseState.PARSE_FILE_TYPE;
                return true;

            case PARSE_FILE_TYPE:
                if (available < 1) {
                    result.setNeedMoreData(true);
                    return false;
                }
                state = ParseState.PARSE_OPERATE_TYPE;
                return true;

            case PARSE_OPERATE_TYPE:
                if (available < 1) {
                    result.setNeedMoreData(true);
                    return false;
                }
                // 现在position-2, position-1, position 三个位置分别是frameType, fileType, operateType
                FileMessageFrameParseUtil.executeFileBasicTypeParse(
                        buffer[position - 2],
                        buffer[position - 1],
                        buffer[position],
                        currentFrame);
                position++;

                // 根据帧类型决定下一步
                state = ParseState.PARSE_DATA_LENGTH;
                return true;

            case PARSE_DATA_LENGTH:
                if (available < 2) {
                    result.setNeedMoreData(true);
                    return false;
                }
                byte[] lengthBytes = Arrays.copyOfRange(buffer, position, position + 2);
                short dataLength = ByteOrderConvert.bytesToShort(lengthBytes);
                position += 2;

                // 根据帧类型设置不同的字段
                if (currentFrame.getFrameType() == FileMessageFrame.FrameType.UPLOAD
                        || currentFrame.getFrameType() == FileMessageFrame.FrameType.DOWNLOAD) {
                    // UPLOAD/DOWNLOAD帧：dataLength表示文件名长度，还需要读取8字节文件大小
                    currentFrame.setDataLength(dataLength);
                    state = ParseState.PARSE_FILE_LENGTH;
                } else if (currentFrame.getFrameType() == FileMessageFrame.FrameType.ONLINE_TRANSPORT
                        || currentFrame.getFrameType() == FileMessageFrame.FrameType.DATA_TRANSPORT
                        || currentFrame.getFrameType() == FileMessageFrame.FrameType.DATA_TRANSPORT_END) {
                    // ONLINE_TRANSPORT等帧：dataLength表示数据内容长度
                    currentFrame.setFrameSumLength(dataLength);
                    dataContentLength = dataLength;
                    state = ParseState.PARSE_DATA_CONTENT;
                } else {
                    // 其他类型帧暂不处理
                    log.warn("未知帧类型: {}", currentFrame.getFrameType());
                    state = ParseState.PARSE_COMPLETE;
                }
                return true;

            case PARSE_FILE_LENGTH:
                if (available < 8) {
                    result.setNeedMoreData(true);
                    return false;
                }
                byte[] fileLengthBytes = Arrays.copyOfRange(buffer, position, position + 8);
                try {
                    currentFrame.setFileLength(ByteOrderConvert.bytesToLong(fileLengthBytes));
                } catch (Exception e) {
                    log.error("解析文件长度失败", e);
                }
                position += 8;

                // UPLOAD/DOWNLOAD帧的数据内容长度是dataLength
                dataContentLength = currentFrame.getDataLength();
                state = ParseState.PARSE_DATA_CONTENT;
                return true;

            case PARSE_DATA_CONTENT:
                if (dataContentLength == 0) {
                    // 无数据内容，直接完成
                    state = ParseState.PARSE_COMPLETE;
                    return true;
                }

                if (available < dataContentLength) {
                    result.setNeedMoreData(true);
                    return false;
                }

                // 读取数据内容
                byte[] content = Arrays.copyOfRange(buffer, position, position + dataContentLength);
                position += dataContentLength;

                // 解析数据内容为字符串（对于需要的帧类型）
                if (currentFrame.getFrameType() == FileMessageFrame.FrameType.UPLOAD
                        || currentFrame.getFrameType() == FileMessageFrame.FrameType.DOWNLOAD
                        || currentFrame.getFrameType() == FileMessageFrame.FrameType.ONLINE_TRANSPORT
                        || currentFrame.getFrameType() == FileMessageFrame.FrameType.DATA_TRANSPORT
                        || currentFrame.getFrameType() == FileMessageFrame.FrameType.DATA_TRANSPORT_END) {
                    try {
                        currentFrame.setData(new String(content, "UTF-8"));
                    } catch (UnsupportedEncodingException e) {
                        log.error("解析数据内容编码失败", e);
                    }
                }

                state = ParseState.PARSE_COMPLETE;
                return true;

            case PARSE_COMPLETE:
                // 帧解析完成
                result.getCompleteFrames().add(currentFrame);
                log.debug("成功解析帧: type={}, index={}, dataLength={}",
                        currentFrame.getFrameType(),
                        currentFrame.getFrameIndex(),
                        dataContentLength);

                // 重置状态，准备解析下一帧
                currentFrame = null;
                dataContentLength = 0;
                state = ParseState.PARSE_END_FRAME;

                // 检查是否还有数据可以继续解析
                if (buffer.length - position > 0) {
                    return true; // 继续解析下一帧
                } else {
                    return false; // 没有更多数据
                }

            default:
                log.error("未知的解析状态: {}", state);
                return false;
        }
    }

    /**
     * 压缩缓存，移除已消费的字节
     */
    private void compactBuffer() {
        if (position > 0) {
            byte[] buffer = pendingBuffer.toByteArray();
            if (position >= buffer.length) {
                // 全部消费完
                pendingBuffer.reset();
                position = 0;
            } else if (position > buffer.length / 2) {
                // 已消费超过一半，进行压缩
                byte[] remaining = Arrays.copyOfRange(buffer, position, buffer.length);
                pendingBuffer.reset();
                pendingBuffer.write(remaining, 0, remaining.length);
                position = 0;
                log.debug("压缩缓存: 剩余{}字节", remaining.length);
            }
        }
    }

    /**
     * 获取当前缓存中的字节数
     */
    public int getPendingBytesCount() {
        return pendingBuffer.size() - position;
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
        state = ParseState.PARSE_END_FRAME;
        pendingBuffer.reset();
        currentFrame = null;
        position = 0;
        dataContentLength = 0;
        log.debug("解析器已重置");
    }
}
