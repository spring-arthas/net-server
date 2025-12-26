package com.alibaba.server.nio.model;

import com.alibaba.server.nio.model.constant.ChannelEventModelEnum;
import com.google.common.collect.Lists;
import lombok.Data;
import java.nio.channels.SelectionKey;
import java.util.List;

/**
 * 通道事件模型数据
 * 最原始的数据模型，收集从nio读事件获取到的元数据信息
 * 
 * @author: YSFY
 * @Date: 2020-11-21 11:39
 * @Pacage_name: com.alibaba.server.nio.model
 * @Project_Name: net-server
 * @Description: 事件模型
 */

@Data
public class ChannelEventModel {

    /**
     * 记录该对象创建时是对应到哪个socketChannel可选键产生的
     */
    private SelectionKey selectionKey;
    /**
     * 该可选键对应的本地socketChannel所表示的远端地址
     */
    private String remoteAddress;
    /**
     * 该可选键对应的本地socketChannel所表示的服务端本地地址（该地址用于与远端remoteAddress匹配）只有在文字传输场景会使用
     */
    private String localAddress;
    /**
     * 即是第几个selectionKey产生的该对象
     */
    private Integer index;
    /**
     * 该socketChannel可选键产生的事件数据对应哪个业务类型（聊天or文件上传or文件下载）
     */
    private ChannelEventModelEnum eventModelEnum;
    /**
     * 本次记录的事件数据的处理结果
     */
    private String eventResult;

    /**
     * 完整数据流,list中每一个byte[]都为一次完整数据流,有序集合
     */
    // private List<GroupData> waitHandleDataList = Lists.newArrayList();

    /**
     * 记录每次读事件一次就绪时产生的所有原始数据，有序集合
     */
    // private List<GroupData> originList = Lists.newLinkedList();

    /**
     * 收集本次socketChannel可选键产生的字节数据，也需要按序记录
     */
    @Data
    public class GroupData {
        /**
         * 当前bytes长度
         */
        private int length;
        /**
         * 当前bytes帧序号
         */
        private int index;
        /**
         * 当前bytes数据
         */
        private byte[] bytes;
        /**
         * 当前帧是否是结束帧(是:1,否:0)
         */
        private byte endFrame;
        /**
         * 当前帧是否处理完
         */
        private String status;
    }
}
