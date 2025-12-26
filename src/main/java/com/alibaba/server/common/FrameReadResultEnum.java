package com.alibaba.server.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 是否的枚举
 *
 * 仅用于存储层，DTO以上请声明转换为boolean使用
 *
 * @author JiaJu Zhuang
 * @date 2020/1/10 10:40
 **/
@Getter
@AllArgsConstructor
public enum FrameReadResultEnum {
    NEED_HANDLE(0, "需要处理"),
    END(-1, "流结尾-1"),
    LESS_THAN_16(-2, "少于16个字节"),
    SUM_LENGTH_ERROR(-3, "帧总长度错误"),
    INVALID(-4, "无效边界"),
    CONTINUE(-30, "继续");

    private int code;
    private String desc;
}
