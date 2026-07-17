package com.alibaba.server.common;

import lombok.Getter;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 文件传输状态枚举
 *
 * @author JiaJu Zhuang
 * @date 2020/1/10 10:40
 **/
@Getter
public enum FileTaskStatusEnum {
    WAIT_UPLOAD(0, "待上传"),
    UPLOADING(1, "上传中"),
    UPLOAD_SUCCESS(2, "上传成功"),
    UPLOAD_FAIL(3, "上传失败"),
    WAIT_DOWNLOAD(4, "待下载"),
    DOWNLOADING(5, "下载中"),
    DOWNLOAD_SUCCESS(6, "下载成功"),
    DOWNLOAD_FAIL(7, "下载失败"),
    PAUSED(8, "已暂停"),
    ;

    private int code;
    private String desc;

    FileTaskStatusEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
