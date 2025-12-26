package com.alibaba.server.nio.model.file;

import lombok.Data;

/**
 * 文件上传帧模型
 * 
 * 协议格式：定长头部(8字节) + 变长数据
 * +--------+--------+--------+--------+--------+
 * | Magic | Type | Flags | Length | Data |
 * | 2字节 | 1字节 | 1字节 | 4字节 | N字节 |
 * +--------+--------+--------+--------+--------+
 * 
 * @author YSFY
 */
@Data
public class FileUploadFrame {

    /**
     * 魔数：用于帧边界识别
     */
    public static final byte[] MAGIC = { (byte) 0xFA, (byte) 0xCE };

    /**
     * 帧头固定长度
     */
    public static final int HEADER_LENGTH = 8;

    /**
     * 帧类型枚举
     */
    public enum FrameType {
        /**
         * 元数据帧：包含文件名、大小、类型等信息
         */
        META_FRAME(0x01, "元数据帧"),
        /**
         * 数据帧：包含文件字节流数据
         */
        DATA_FRAME(0x02, "数据帧"),
        /**
         * 结束帧：标识文件传输结束
         */
        END_FRAME(0x03, "结束帧"),
        /**
         * 确认帧：服务端发送给客户端的确认响应
         */
        ACK_FRAME(0x04, "确认帧");

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
        public static FrameType fromCode(int code) {
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

    /**
     * 帧类型
     */
    private FrameType type;

    /**
     * 标志位
     */
    private byte flags;

    /**
     * 数据长度
     */
    private int dataLength;

    /**
     * 数据内容（原始字节）
     */
    private byte[] data;

    // ============= 元数据帧专用字段 =============

    /**
     * 文件名称
     */
    private String fileName;

    /**
     * 文件大小（字节）
     */
    private long fileSize;

    /**
     * 文件类型（扩展名）
     */
    private String fileType;

    /**
     * 任务ID（用于关联同一次上传的多个帧）
     */
    private String taskId;

    // ============= 辅助方法 =============

    /**
     * 是否为最后一帧
     */
    public boolean isLastFrame() {
        return (flags & FLAG_LAST_FRAME) != 0;
    }

    /**
     * 是否需要确认
     */
    public boolean needAck() {
        return (flags & FLAG_NEED_ACK) != 0;
    }

    /**
     * 获取数据内容的字符串形式
     */
    public String getDataAsString() {
        if (data == null || data.length == 0) {
            return null;
        }
        return new String(data, java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return "FileUploadFrame{" +
                "type=" + type +
                ", flags=" + flags +
                ", dataLength=" + dataLength +
                ", fileName='" + fileName + '\'' +
                ", fileSize=" + fileSize +
                ", taskId='" + taskId + '\'' +
                '}';
    }
}
