package com.alibaba.server.nio.model.resumable;

import lombok.Data;

import java.nio.charset.StandardCharsets;

/**
 * 断点续传协议帧定义
 */
@Data
public class ResumableFrame {

    public static final byte[] MAGIC = new byte[]{(byte) 0xFA, (byte) 0xCE};
    public static final int HEADER_LENGTH = 8;
    /**
     * 帧类型枚举
     */
    public enum FrameType {
        /**
         * 元数据帧：包含文件名、大小、类型等信息
         */
        TYPE_UPLOAD_CHECK(0x50, "元数据帧"),
        /**
         * 数据帧：包含文件字节流数据
         */
        TYPE_UPLOAD_DATA(0x51, "数据帧"),
        /**
         * 结束帧：标识文件传输结束
         */
        TYPE_UPLOAD_ACK(0x52, "结束帧"),
        /**
         * 确认帧：服务端发送给客户端的确认响应
         */
        TYPE_UPLOAD_END(0x53, "确认帧"),
        /**
         * 目录新建请求
         */
        TYPE_DOWNLOAD_CHECK(0x54, "目录新建请求"),
        /**
         * 目录删除请求
         */
        TYPE_DOWNLOAD_DATA(0x55, "目录删除请求"),
        /**
         * 目录更新请求
         */
        TYPE_DOWNLOAD_ACK(0x56, "目录更新请求"),
        /**
         * 目录移动请求
         */
        TYPE_DOWNLOAD_END(0x57, "目录移动请求");

        private final int code;
        private final String description;

        FrameType(int code, String description) {
            this.code = code;
            this.description = description;
        }

        public int getCode() {
            return code;
        }

        public String getDescription() {
            return description;
        }

        /**
         * 根据编码值获取帧类型
         */
        public static ResumableFrame.FrameType fromCode(int code) {
            for (FrameType type : values()) {
                if (type.code == code) {
                    return type;
                }
            }
            return null;
        }
    }

    /**
     * 标志位定义
     */
    public static final byte FLAG_LAST_FRAME = 0x01; // bit0: 是否最后一帧
    public static final byte FLAG_NEED_ACK = 0x02; // bit1: 是否需要确认

    private FrameType type;
    private byte flags;
    private int length;
    private byte[] data;

    public ResumableFrame() {}

    public ResumableFrame(FrameType type, byte[] data) {
        this.type = type;
        this.data = data;
        this.length = (data == null) ? 0 : data.length;
    }

    public FrameType getType() {
        return type;
    }

    public void setType(FrameType type) {
        this.type = type;
    }

    public byte getFlags() {
        return flags;
    }

    public void setFlags(byte flags) {
        this.flags = flags;
    }

    public int getLength() {
        return length;
    }

    public void setLength(int length) {
        this.length = length;
    }

    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }

    public String getDataAsString() {
        if (data == null) return null;
        return new String(data, StandardCharsets.UTF_8);
    }
}
