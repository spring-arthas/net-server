package com.alibaba.server.nio.model.constant;

/**
 * NIO事件类型枚举
 * @author spring
 * */
public enum EventModelEnum {

    CHAT_TASK("CHAT_TASK", "登录帧"),
    FILE_UPLOAD_TASK("FILE_UPLOAD_TASK", "登出帧"),
    FILE_DOWNLOAD_TASK("FILE_DOWNLOAD_TASK", "登出帧");

    private String name;

    private String description;

    EventModelEnum(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }
}
