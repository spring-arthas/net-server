package com.alibaba.server.nio.model.constant;

/**
 * 通道事件模型数据枚举
 * @author spring
 * */
public enum ChannelEventModelEnum {
    TEXT_TRANSMISSION("TEXT_TRANSMISSION", "文字传输"),
    FILE_UPLOAD("FILE_UPLOAD", "上传"),
    FILE_DOWNLOAD("FILE_DOWNLOAD", "下载"),
    FILE_RESUME_UPLOAD("FILE_RESUME_UPLOAD", "断点上传"),
    FILE_RESUME_DOWNLOAD("FILE_RESUME_DOWNLOAD", "断点下载"),;

    private String name;
    private String description;
    ChannelEventModelEnum(String name, String description) {
        this.name = name;
        this.description = description;
    }
    public String getName() {
        return name;
    }
    public String getDescription() {
        return description;
    }

    public static ChannelEventModelEnum getByName(String name) {
        for (ChannelEventModelEnum value : values()) {
            if (value.getName().equals(name)) {
                return value;
            }
        }
        return null;
    }
}
