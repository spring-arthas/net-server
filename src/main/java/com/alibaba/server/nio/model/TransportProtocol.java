package com.alibaba.server.nio.model;

import lombok.Data;

import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.Map;

/**
 * @author: YSFY
 * @Date: 2020-11-21 21:07
 * @Pacage_name: com.alibaba.server.nio.model
 * @Project_Name: net-server
 * @Description: 通道传输协议
 */
@Data
public class TransportProtocol {

    /**
     * 通道
     */
    private SocketChannel socketChannel;

    /**
     * 原始数据
     * */
    private List<Map<String, Object>> originList;

    /**
     * 当前事件已缓存的原始数据字节数组总长度
     * */
    private Integer length;

    /**
     * 真实数据
     * */
    private List<Object> realList;
}
