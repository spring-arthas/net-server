package com.alibaba.server.nio.model;

import com.alibaba.server.nio.model.constant.EventModelEnum;
import com.google.common.collect.Lists;
import lombok.Data;
import java.nio.channels.SelectionKey;
import java.util.List;

/**
 * NIO订阅事件模型
 * @author: YSFY
 * @Date: 2020-11-21 11:39
 * @Pacage_name: com.alibaba.server.nio.model
 * @Project_Name: net-server
 * @Description: 事件模型
 */

@Data
public class EventModel {

    /**
    * 当前事件模型下所属的selectionKey
    * */
    private SelectionKey selectionKey;

    /**
     * 当前事件由那个远程连接触发记录其远程地址
     * */
    private String remoteAddress;

    /**
     * 当前事件本地地址
     * */
    private String localAddress;

    /**
     * 事件模型序号
     * */
    private Integer index;

    /**
     * 事件处理类型
     * */
    private EventModelEnum eventModelEnum;

    /**
     * 事件处理结果
     * */
    private String eventResult;

    /**
     * 完整数据流,list中每一个byte[]都为一次完整数据流,有序集合
     * */
    private List<GroupData> completeList = Lists.newArrayList();

    /**
     * 记录每次读事件一次就绪时产生的所有原始数据，有序集合
     * */
    private List<GroupData> originList = Lists.newLinkedList();

    /**
     * 每次socketChannel.read产生的数据
     */
    @Data
    public class GroupData {

        /**
         * 当前bytes长度
         */
        private int length;

        /**
         * 当前bytes帧序号
         * */
        private int index;

        /**
         * 当前bytes数据
         * */
        private byte[] bytes;

        /**
         * 当前帧是否是结束帧(是:1,否:0)
         * */
        private byte endFrame;

        /**
         * 当前帧是否处理完
         * */
        private String status;
    }
}
