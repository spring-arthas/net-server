package com.alibaba.server.common;

import lombok.Getter;

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
public enum YesOrNoEnum {
    /**
     * YES
     */
    Y,
    /**
     * NO
     */
    N,
    // 分号
    ;

    public static boolean isYes(String code) {
        return Y.name().equals(code);
    }

    public static boolean isNo(String code) {
        return N.name().equals(code);
    }

    public static Boolean toBoolean(String code) {
        if (code == null) {
            return null;
        } else if (isYes(code)) {
            return Boolean.TRUE;
        } else if (isNo(code)) {
            return Boolean.FALSE;
        } else {
            throw new IllegalArgumentException(code);
        }
    }

    public static YesOrNoEnum fromBoolean(Boolean bool) {
        if (bool == null) {
            return null;
        } else {
            return bool ? Y : N;
        }
    }

    public static List<String> toList() {
        return Arrays.stream(YesOrNoEnum.values()).map(YesOrNoEnum::name).collect(Collectors.toList());
    }

    public static String toDesc(String code){
        if (isYes(code)) {
            return "是";
        } else if (isNo(code)) {
            return "否";
        } else {
            return null;
        }
    }
}
