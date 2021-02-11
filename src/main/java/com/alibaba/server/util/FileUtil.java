package com.alibaba.server.util;

import lombok.extern.slf4j.Slf4j;

/**
 * @Auther: YSFY
 * @Date: 2020-10-03 13:33
 * @Pacage_name: com.alibaba.server.util
 * @Project_Name: net-server
 * @Description: 文件处理类
 */

@Slf4j
@SuppressWarnings("all")
public class FileUtil {

    /**
     * 获取实现了ChannelHandler类的子类
     *
     * */
    public static void getFiles() {

        String path = Thread.currentThread().getContextClassLoader().getResource("").getPath();
        System.out.println(path);
    }
}
