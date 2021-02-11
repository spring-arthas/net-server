package com.alibaba.server.util;

import lombok.extern.slf4j.Slf4j;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.util.Properties;

/**
 * @Auther: YSFY
 * @Date: 2020/10/3
 * @Pacage_name: com.alibaba.server.common
 * @Project_Name: net-server
 * @Description: 配置文件读取类
 */

@Slf4j
@SuppressWarnings("all")
public class PropertiesUtil {
    private static Properties pro = null;

    private PropertiesUtil() {

    }

    public static Properties getInstance() {
        return pro;
    }

    public static void initProperties() {
        InputStream inputStream =  Thread.currentThread().getContextClassLoader().getResourceAsStream("server.properties");
        BufferedReader bf = new BufferedReader(new InputStreamReader(inputStream));
        try {
            pro = new Properties();
            pro.load(bf);
        } catch (IOException e) {
            log.error("PropertiesUtil | --> 读取配置文件异常, error = {}", e.getLocalizedMessage());
        }
    }

    public static String getValue(String key) {
        String value = null;
        try {
            if(null != pro && !pro.isEmpty()) {
                if(pro.containsKey(key)) {
                    return pro.getProperty(key);
                }
            }
        } catch (NumberFormatException e) {
            log.error("PropertiesUtil | --> 读取配置文件值异常, error = {}", e.getLocalizedMessage());
        }

        return value;
    }
}
