package com.alibaba.server.nio.model;

import com.google.common.collect.Lists;
import lombok.Data;

import java.util.List;

/**
 * @author: YSFY
 * @Date: 2020-12-22 12:07
 * @Pacage_name: com.alibaba.server.nio.model
 * @Project_Name: net-server
 * @Description: 通道读取事件缓存模型
 */
@Data
public class ChannelEventDataCacheModel {
    /**
     * 当前通道最新的GroupData序号, 默认为1
     */
    private int newLatestIndex = 1;
    /**
     * 当前通道名称
     */
    private String channelAddress;
    /**
     * 数据集合
     */
    private List<ChannelEventModel.GroupData> waitHandleDataList = Lists.newArrayList();
}
