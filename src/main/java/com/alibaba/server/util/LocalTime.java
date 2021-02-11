package com.alibaba.server.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * @Auther: YSFY
 * @Date: 2019/9/7 16:02
 * @Pacage_name: com.dh.spring.util.time
 * @Project_Name: spring-boot-project
 * @Description:
 */
public class LocalTime {
    private static final DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter dateTimeFormatterFirst = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    public LocalTime() {
    }

    public static String formatDate(LocalDateTime localDateTime) {
        return dateTimeFormatter.format(localDateTime);
    }

    public static String formatDateFirst(LocalDateTime localDateTime) {
        return dateTimeFormatterFirst.format(localDateTime);
    }

    public static LocalDateTime parse(String strDate) {
        return LocalDateTime.parse(strDate, dateTimeFormatter);
    }
}
