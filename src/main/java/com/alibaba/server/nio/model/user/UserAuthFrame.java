package com.alibaba.server.nio.model.user;

import com.alibaba.server.nio.model.file.FileUploadFrame;
import lombok.Data;

/**
 * 用户认证帧模型
 * 
 * 复用 FileUploadFrame 协议格式
 * 
 * @author spring
 */
@Data
public class UserAuthFrame {

    /**
     * 魔数：复用 FileUploadFrame.MAGIC
     */
    public static final byte[] MAGIC = FileUploadFrame.MAGIC;

    /**
     * 帧头长度：复用 FileUploadFrame.HEADER_LENGTH
     */
    public static final int HEADER_LENGTH = FileUploadFrame.HEADER_LENGTH;

    /**
     * 用户名最大长度
     */
    public static final int MAX_USERNAME_LENGTH = 5;

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
        /** 用户名超过5个字符 */
        public static final String USER_NAME_TOO_LONG = "USER_NAME_TOO_LONG";
        /** 用户名已存在 */
        public static final String USER_NAME_DUPLICATE = "USER_NAME_DUPLICATE";
        /** 用户不存在 */
        public static final String USER_NOT_FOUND = "USER_NOT_FOUND";
        /** 密码错误 */
        public static final String PASSWORD_ERROR = "PASSWORD_ERROR";
        /** 未登录 */
        public static final String NOT_LOGGED_IN = "NOT_LOGGED_IN";
        /** 数据库错误 */
        public static final String DB_ERROR = "DB_ERROR";
        /** 请求无效 */
        public static final String INVALID_REQUEST = "INVALID_REQUEST";
    }
}
