package com.alibaba.server.util;

import lombok.extern.slf4j.Slf4j;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.util.Locale;
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
        pro = new Properties();
        // 仅 Windows 环境下支持通过 -Dnet.server.config=<外部文件路径> 指定外部配置文件；
        // macOS / Linux 以及其他未指定该参数的情况，一律按原有的 classpath 方式读取。
        if (isWindows()) {
            String externalPath = System.getProperty("net.server.config");
            if (externalPath != null && !externalPath.trim().isEmpty()) {
                BufferedReader bf = null;
                try {
                    File externalFile = new File(externalPath.trim());
                    if (externalFile.exists() && externalFile.isFile()) {
                        bf = new BufferedReader(new InputStreamReader(new FileInputStream(externalFile), "UTF-8"));
                        pro.load(bf);
                        log.info("PropertiesUtil | --> 已从外部配置文件读取: {}", externalFile.getAbsolutePath());
                        return;
                    } else {
                        log.warn("PropertiesUtil | --> 指定的外部配置文件不存在, 回退到 classpath: {}", externalPath);
                    }
                } catch (Exception e) {
                    log.error("PropertiesUtil | --> 读取外部配置文件异常, 回退到 classpath, error = {}", e.getLocalizedMessage());
                } finally {
                    if (bf != null) {
                        try {
                            bf.close();
                        } catch (IOException e) {
                            // ignore
                        }
                    }
                }
            }
        }

        // 原有逻辑：从 classpath 读取
        BufferedReader bf = null;
        try {
            InputStream inputStream = Thread.currentThread().getContextClassLoader().getResourceAsStream("server.properties");
            if (inputStream == null) {
                log.error("PropertiesUtil | --> classpath 下未找到 server.properties");
                return;
            }
            bf = new BufferedReader(new InputStreamReader(inputStream));
            pro.load(bf);
        } catch (IOException e) {
            log.error("PropertiesUtil | --> 读取配置文件异常, error = {}", e.getLocalizedMessage());
        } finally {
            if (bf != null) {
                try {
                    bf.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
    }

    /**
     * 判断当前操作系统是否为 Windows。
     */
    private static boolean isWindows() {
        String osName = System.getProperty("os.name", "");
        return osName != null && osName.toLowerCase(Locale.ROOT).contains("win");
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
