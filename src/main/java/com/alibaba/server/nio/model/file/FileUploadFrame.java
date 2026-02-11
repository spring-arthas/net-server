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
        ACK_FRAME(0x04, "确认帧"),
        /**
         * 断点检查帧：客户端请求检查文件上传断点
         */
        RESUME_CHECK(0x05, "断点检查帧"),
        /**
         * 断点应答帧：服务端返回已上传大小和续传信息
         */
        RESUME_ACK(0x06, "断点应答帧"),

        // ========== 目录操作帧 (0x10-0x1F) ==========
        /**
         * 目录新建请求
         */
        DIR_CREATE_REQ(0x10, "目录新建请求"),
        /**
         * 目录删除请求
         */
        DIR_DELETE_REQ(0x11, "目录删除请求"),
        /**
         * 目录更新请求
         */
        DIR_UPDATE_REQ(0x12, "目录更新请求"),
        /**
         * 目录移动请求
         */
        DIR_MOVE_REQ(0x13, "目录移动请求"),
        /**
         * 目录操作响应
         */
        DIR_RESPONSE(0x14, "目录操作响应"),
        /**
         * 获取当前用户顶层和第二层所有目录数据
         */
        DIR_USER_GET_TWO_LEVEL_REQ(0x15, "获取当前用户顶层和第二层目录数据"),

        // ========== 目录文件上传帧 (0x20-0x2F) ==========
        /**
         * 目录文件元数据帧
         */
        DIR_FILE_META(0x20, "目录文件元数据"),
        /**
         * 目录文件数据帧
         */
        DIR_FILE_DATA(0x21, "目录文件数据"),
        /**
         * 目录文件结束帧
         */
        DIR_FILE_END(0x22, "目录文件结束"),
        /**
         * 目录文件确认帧
         */
        DIR_FILE_ACK(0x23, "目录文件确认"),

        // ========== 用户认证帧 (0x30-0x3F) ==========
        /**
         * 用户注册请求
         */
        USER_REGISTER_REQ(0x30, "用户注册请求"),
        /**
         * 用户登录请求
         */
        USER_LOGIN_REQ(0x31, "用户登录请求"),
        /**
         * 用户修改密码请求
         */
        USER_CHANGE_PWD_REQ(0x32, "用户修改密码请求"),
        /**
         * 用户退出登录请求
         */
        USER_LOGOUT_REQ(0x33, "用户退出登录请求"),
        /**
         * 用户操作响应
         */
        USER_RESPONSE(0x34, "用户操作响应"),
        /**
         * 用户好友列表
         */
        USER_FRIEND_LIST_REQ(0x35, "用户好友列表"),
        /**
         * 好友搜索
         */
        USER_FRIEND_QUERY_REQ(0x36, "好友搜索"),

        // ========== 文件操作帧 (0x40-0x4F) ==========
        /**
         * 文件列表分页请求
         */
        FILE_LIST_REQ(0x40, "文件列表请求"),
        /**
         * 文件删除请求
         */
        FILE_DELETE_REQ(0x41, "文件删除请求"),
        /**
         * 文件详情请求
         */
        FILE_DETAIL_REQ(0x42, "文件详情请求"),
        /**
         * 文件操作响应
         */
        FILE_RESPONSE(0x43, "文件操作响应");

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
