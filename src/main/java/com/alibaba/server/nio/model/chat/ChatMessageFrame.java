package com.alibaba.server.nio.model.chat;

import lombok.Data;

/**
 * @author: YSFY
 * @Date: 2020-11-21 21:09
 * @Pacage_name: com.alibaba.server.nio.model.chat
 * @Project_Name: net-server
 * @Description: 聊天帧
 */
@Data
public class ChatMessageFrame {

    /**
     * 是否是结束帧 1B   帧类型 1B  帧索引  4B   数据长度 2B
     */
    public static final Byte COMMON = 1 + 1 + 4 + 2;

    public enum FrameType {
        LONGIN("LONGIN", "登录帧", "00000000"),
        LOGOUT("LOGOUT", "登出帧", "00000001"),
        TEXT("TEXT", "文本帧", "00000010"),
        REFRESH("REFRESH", "刷新列表帧", "00000011"),
        REGISTER("REGISTER", "注册帧", "00000100"),
        HEART("HEART", "心跳帧", "00000101"),
        QUERY_USER("QUERY_USER", "查询用户帧", "00001100");

        private String frameType;

        private String description;

        private String bit;

        public String getFrameType() {
            return frameType;
        }

        public String getDescription() {
            return description;
        }

        public String getBit() {
            return bit;
        }

        FrameType(String frameType, String description, String bit) {

            this.frameType = frameType;
            this.description = description;
            this.bit = bit;
        }
    }

    /**
     * 帧类型
     * */
    private FrameType frameType;

    /**
     * 帧数据总长度
     * */
    private Short frameLength;

    /**
     * 是否是结束帧
     * */
    private Byte endFrame;

    /**
     * 帧索引
     * */
    private Integer index;

    /**
     * 数据
     * */
    private String data;
}
