package com.alibaba.server.nio.core.server;

import com.alibaba.server.common.BasicConstant;
import com.alibaba.server.nio.model.constant.ChannelEventModelEnum;
import com.alibaba.server.nio.repository.user.service.dto.UserDTO;
import com.alibaba.server.util.PropertiesUtil;
import com.google.common.collect.Maps;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @Auther: YSFY
 * @Date: 2020-11-14 16:32
 * @Pacage_name: com.alibaba.server.nio.core
 * @Project_Name: net-server
 * @Description: 基础启动配置服务
 *
 *      加载配置文件记录配置文件数据 --> 创建启动Selector
 */

@Slf4j
@SuppressWarnings("all")
public class BasicServer {
    public static String SERVER_IP = "127.0.0.1", FILE_PATH = BasicConstant.DATA_EMPTY, NETTY_SERVER_PROTOCOL_PORT = "";
    // 公共共享数据集合
    private static final Map<String, Object> map = new ConcurrentHashMap<>();
    public final static Lock chatLock = new ReentrantLock(true);
    public final static Lock fileLock = new ReentrantLock(true);
    public static ClassPathXmlApplicationContext classPathXmlApplicationContext = null;

    /**
     * 启动基础配置服务
     */
    public static void startupBasicServer() {
        // 1、读取配置文件
        loadConfigProperties();

        // 2、设置枚举
        setEnumsType();
    }

    /**
     * 读取配置文件数据并设置进缓存
     * */
    private static void loadConfigProperties() {
        PropertiesUtil.initProperties();
        map.put(BasicConstant.CPU_CORE_COUNT, Runtime.getRuntime().availableProcessors());
        map.put(BasicConstant.OS_NAME, System.getProperty(BasicConstant.OS_NAME));
        map.put(BasicConstant.USER, new HashMap<String, UserDTO>());
        Iterator iterator = PropertiesUtil.getInstance().stringPropertyNames().iterator();
        while (iterator.hasNext()) {
            String param = iterator.next().toString();

            if(!map.containsKey(param)) {
                map.put(param, PropertiesUtil.getValue(param));
            }
        }
        log.info("BasicServer：读取配置文件[server.properties], result = success, Server print Config");

        System.out.println("/+------------------------------------------ 开始读取配置 ------------------------------------------+/");
        System.out.println();
        Map.Entry<String, Object> entry = null;
        if(!map.isEmpty()) {
            iterator = map.entrySet().iterator();
            while (iterator.hasNext()) {
                System.out.println("/+------------------------------------------ " + (entry = (Map.Entry<String, Object>) iterator.next()).getKey() + " = " + entry.getValue() + " ------------------------------------------+/");
            }
        }
        System.out.println();
        System.out.println("/+------------------------------------------ 配置读取完成 ------------------------------------------+/");
        log.info("BasicServer：读取配置文件[server.properties], result = success, Server print Config");
    }

    /**
     * 设置枚举
     */
    private static void setEnumsType() {

        // 设置文件枚举
       /* FileMessageFrame.FrameType[] fileFrameTypes = FileMessageFrame.FrameType.values();
        Map<String, FileMessageFrame.FrameType> fileFrameTypeMap = Maps.newHashMap();
        for(FileMessageFrame.FrameType frameType : fileFrameTypes) {
            fileFrameTypeMap.put(frameType.getBit(), frameType);
        }
        map.put(BasicConstant.FILE_MESSAGE_FRAME_TYPE, fileFrameTypeMap);*/

        // 设置Nio事件枚举
        ChannelEventModelEnum[] eventModelEnums = ChannelEventModelEnum.values();
        Map<String, ChannelEventModelEnum> eventModelEnumMap = Maps.newHashMap();
        for(ChannelEventModelEnum eventModelEnum : eventModelEnums) {
            eventModelEnumMap.put(eventModelEnum.getName(), eventModelEnum);
        }
        map.put(BasicConstant.EVENT_TYPE, eventModelEnumMap);
    }

    /**
     * 返回缓存Map对象
     * */
    public static Map<String, Object> getMap() {
        return map;
    }
}
