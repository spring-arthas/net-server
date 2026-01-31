package com.alibaba.server.nio.model.file;

import lombok.Data;

/**
 * 目录操作帧模型
 * 
 * 复用 FileUploadFrame 协议格式：
 * +--------+--------+--------+--------+--------+
 * | Magic | Type | Flags | Length | Data |
 * | 2字节 | 1字节 | 1字节 | 4字节 | N字节 |
 * +--------+--------+--------+--------+--------+
 * 
 * @author spring
 */
@Data
public class DirectoryFrame {

    /**
     * 魔数：复用 FileUploadFrame.MAGIC
     */
    public static final byte[] MAGIC = FileUploadFrame.MAGIC;

    /**
     * 帧头长度：复用 FileUploadFrame.HEADER_LENGTH
     */
    public static final int HEADER_LENGTH = FileUploadFrame.HEADER_LENGTH;

    /**
     * 帧类型
     */
    private FileUploadFrame.FrameType type;

    /**
     * 标志位
     */
    private byte flags;

    /**
     * 数据长度
     */
    private int dataLength;

    /**
     * 帧数据（JSON格式）
     */
    private byte[] data;

    /**
     * 获取数据字符串形式
     */
    public String getDataAsString() {
        if (data == null) {
            return null;
        }
        return new String(data, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * 错误码定义
     */
    public static class ErrorCode {
        public static final String DIR_NAME_TOO_LONG = "DIR_NAME_TOO_LONG";
        public static final String DIR_ROOT_NOT_EXIST = "DIR_ROOT_NOT_EXIST";
        public static final String DIR_NAME_DUPLICATE = "DIR_NAME_DUPLICATE";
        public static final String DIR_HAS_CHILDREN = "DIR_HAS_CHILDREN";
        public static final String DIR_NOT_FOUND = "DIR_NOT_FOUND";
        public static final String PARENT_NOT_DIR = "PARENT_NOT_DIR";
        public static final String FS_ERROR = "FS_ERROR";
        public static final String DB_ERROR = "DB_ERROR";
        public static final String INVALID_REQUEST = "INVALID_REQUEST";
    }

    /**
     * 目录操作类型
     */
    public static class Action {
        public static final String CREATE = "CREATE";
        public static final String DELETE = "DELETE";
        public static final String UPDATE = "UPDATE";
        public static final String MOVE = "MOVE";
    }
}
