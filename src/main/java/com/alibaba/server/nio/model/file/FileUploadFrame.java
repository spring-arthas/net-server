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
        /**
         * 批量递归删除目录和文件请求
         */
        DIR_BATCH_DELETE_REQ(0x16, "批量递归删除目录和文件请求"),

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
        /**
         * 用户添加好友请求
         */
        USER_FRIEND_ADD_REQ(0x37, "用户添加好友请求"),
        /**
         * 获取好友申请列表
         */
        USER_FRIEND_APPLY_REQ(0x38, "获取好友申请列表"),
        /**
         * 处理好友申请
         */
        USER_FRIEND_APPLY_HANDLE_REQ(0x39, "处理好友申请"),
        /** 好友搜索响应 */
        USER_FRIEND_QUERY_RESPONSE(0x3A, "好友搜索响应"),
        /** 发送好友申请响应 */
        USER_FRIEND_ADD_RESPONSE(0x3B, "发送好友申请响应"),
        /** 好友申请列表响应 */
        USER_FRIEND_APPLY_RESPONSE(0x3C, "好友申请列表响应"),
        /** 处理好友申请响应 */
        USER_FRIEND_APPLY_HANDLE_RESPONSE(0x3D, "处理好友申请响应"),
        /** 好友关系事件推送 */
        USER_FRIEND_EVENT_PUSH(0x3E, "好友关系事件推送"),
        /** 好友列表响应 */
        USER_FRIEND_LIST_RESPONSE(0x3F, "好友列表响应"),

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
        FILE_RESPONSE(0x43, "文件操作响应"),
        /**
         * 文件重命名请求
         */
        FILE_RENAME_REQ(0x44, "文件重命名请求"),
        /**
         * 当前用户头像更新请求
         */
        USER_AVATAR_UPDATE_REQ(0x45, "当前用户头像更新请求"),
        /** 使用持久会话凭证恢复文本连接身份 */
        USER_SESSION_RESUME_REQ(0x46, "用户会话恢复请求"),
        /** 控制连接心跳请求 */
        CONNECTION_HEARTBEAT_REQ(0x47, "控制连接心跳请求"),
        /** 控制连接心跳响应 */
        CONNECTION_HEARTBEAT_RESPONSE(0x48, "控制连接心跳响应"),
        /** 文件移动请求 */
        FILE_MOVE_REQ(0x49, "文件移动请求"),

        // ========== 聊天消息帧 (0x50-0x5F) ==========
        /**
         * 发送聊天消息请求
         */
        CHAT_MSG_SEND_REQ(0x50, "发送聊天消息请求"),
        /**
         * 推送聊天消息
         */
        CHAT_MSG_PUSH(0x51, "推送聊天消息"),
        /**
         * 聊天消息回执
         */
        CHAT_MSG_RESPONSE(0x52, "聊天消息回执"),
        /**
         * 查询聊天历史记录请求
         */
        CHAT_MSG_HISTORY_REQ(0x53, "查询聊天历史记录请求"),

        /**
         * 查询聊天历史记录回执
         */
        CHAT_MSG_HISTORY_RESPONSE(0x54, "查询聊天历史记录回执"),

        /**
         * 聊天消息已读上报
         */
        CHAT_MSG_READ_REQ(0x55, "聊天消息已读上报"),

        /**
         * 聊天消息已读重回执
         */
        CHAT_MSG_READ_RESPONSE(0x56, "聊天消息已读回执"),

        /**
         * 更新好友别名请求
         */
        USER_FRIEND_UPDATE_ALIAS_REQ(0x57, "更新好友别名请求"),

        /**
         * 更新好友别名回执
         */
        USER_FRIEND_UPDATE_ALIAS_RESPONSE(0x58, "更新好友别名回执"),

        /**
         * 更新好友置顶请求
         */
        USER_FRIEND_PIN_UPDATE_REQ(0x5C, "更新好友置顶请求"),

        /**
         * 更新好友置顶回执
         */
        USER_FRIEND_PIN_UPDATE_RESPONSE(0x5D, "更新好友置顶回执"),

        /**
         * 聊天消息操作请求(撤回/表情回应)
         */
        CHAT_MSG_ACTION_REQ(0x59, "聊天消息操作请求"),

        /**
         * 聊天消息操作回执
         */
        CHAT_MSG_ACTION_RESPONSE(0x5A, "聊天消息操作回执"),

        /**
         * 聊天消息操作推送
         */
        CHAT_MSG_ACTION_PUSH(0x5B, "聊天消息操作推送"),

        /**
         * 聊天消息搜索请求
         */
        CHAT_MSG_SEARCH_REQ(0x5E, "聊天消息搜索请求"),

        /**
         * 聊天消息搜索回执
         */
        CHAT_MSG_SEARCH_RESPONSE(0x5F, "聊天消息搜索回执"),

        // ========== 动态帧 (0x60-0x6F) ==========
        /**
         * 新建动态请求
         */
        DYNAMIC_CREATE_REQ(0x60, "新建动态请求"),

        /** 新建动态回执 */
        DYNAMIC_CREATE_RESPONSE(0x61, "新建动态回执"),
        /** 时间线分页请求 */
        DYNAMIC_TIMELINE_REQ(0x62, "动态时间线请求"),
        /** 时间线分页回执 */
        DYNAMIC_TIMELINE_RESPONSE(0x63, "动态时间线回执"),
        /** 点赞、回复和转发请求 */
        DYNAMIC_ACTION_REQ(0x64, "动态互动请求"),
        /** 点赞、回复和转发回执 */
        DYNAMIC_ACTION_RESPONSE(0x65, "动态互动回执"),
        /** 动态详情请求 */
        DYNAMIC_DETAIL_REQ(0x66, "动态详情请求"),
        /** 动态详情回执 */
        DYNAMIC_DETAIL_RESPONSE(0x67, "动态详情回执"),
        /** 删除自己的动态请求 */
        DYNAMIC_DELETE_REQ(0x68, "动态删除请求"),
        /** 删除自己的动态回执 */
        DYNAMIC_DELETE_RESPONSE(0x69, "动态删除回执");

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
    public static final byte FLAG_HAS_OFFSET = 0x04; // bit2: 数据前8字节为大端序文件偏移量

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
     * 数据帧是否携带文件偏移量
     */
    public boolean hasOffset() {
        return (flags & FLAG_HAS_OFFSET) != 0;
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
