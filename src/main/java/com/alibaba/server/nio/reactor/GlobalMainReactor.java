package com.alibaba.server.nio.reactor;

import com.alibaba.server.common.BasicConstant;
import com.alibaba.server.nio.core.server.NioServerContext;
import com.alibaba.server.util.LocalTime;
import lombok.extern.slf4j.Slf4j;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * @Auther: YSFY
 * @Date: 2020-11-21 10:41
 * @Pacage_name: com.alibaba.server.nio.reactor
 * @Project_Name: net-server
 * @Description: Reactor主线程，一个socketChannel --->  多个用户复用  --->  只能有一个subReactor线程
 */

@Slf4j
@SuppressWarnings("all")
public class GlobalMainReactor {

    private static Map<String, Object> subReactorMap = null;

    /**
     * 初始化subReactor线程集合
     * */
    public static Map<String, Object> init() {
        if(null == subReactorMap) {
            return subReactorMap = new HashMap<>();
        }
        return subReactorMap;
    }

    /**
     * 注册SocketChannel到SubReactor线程
     * @param selector 待注册的多路复用器
     * @param socketChannel 待注册的通道
     * @param subReactName 注册的SubReactor类型，是用于聊天的SubReactor还是文件服务
     * @return subReactor 返回socketChannel对应的SubReactor线程
     * */
    public static SubReactor registerStart(Selector selector, SocketChannel socketChannel, String subReactName) {
        SubReactor subReactor = null;
        try {
            if(!socketChannel.isOpen() || !socketChannel.isConnected()) {
                throw new RuntimeException("socketChannel is not open or connected, address = " + NioServerContext.getRemoteAddress(socketChannel));
            }

            String subReactorName = NioServerContext.getRemoteAddress(socketChannel);
            if(!subReactorMap.containsKey(subReactorName)) {
                Map<String, Object> map = new HashMap<>();
                subReactor = new SubReactor(selector, socketChannel, subReactName);
                Thread thread = new Thread(subReactor, "[ " + subReactorName + " ]-subReactor-thread-" + subReactorMap.size());
                map.put(BasicConstant.THREAD, thread);
                map.put(BasicConstant.RUNNABLE, subReactor);
                subReactorMap.put(subReactorName, map);

                thread.start();

                log.info("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] GlobalMainReactor | --> socketChannel subReactor create success, online connections = {}, thread = {}", subReactorMap.size(),  Thread.currentThread().getName());
            }
            return subReactor;
        } catch (Exception e) {
            log.error("[" + LocalTime.formatDate(LocalDateTime.now()) + "] GlobalMainReactor | --> register socketChannel error, error = {}", e.getMessage());
        }
        return null;
    }

    /**
     * 根据socketChannel连接字符串获取其对应的SubReactor线程
     * @param socketChannel 待获取对应SubReactor线程持有的SocketChannel
     * @return subReactor
     * */
    public static SubReactor getSubReactorForSocketChannel(SocketChannel socketChannel) {
        return (SubReactor) ((Map) subReactorMap.get(NioServerContext.getRemoteAddress(socketChannel))).get("RUNNABLE");
    }
}
