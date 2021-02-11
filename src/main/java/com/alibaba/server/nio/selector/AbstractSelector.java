package com.alibaba.server.nio.selector;

import com.alibaba.server.common.BasicConstant;
import com.alibaba.server.nio.core.server.NioServerContext;
import com.alibaba.server.nio.model.EventModel;
import com.alibaba.server.nio.model.SocketChannelContext;
import com.alibaba.server.nio.model.constant.EventModelEnum;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;

import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Optional;

/**
 * @Auther: YSFY
 * @Date: 2020-11-21 11:30
 * @Pacage_name: com.alibaba.server.nio.selector
 * @Project_Name: net-server
 * @Description: 公共Selector处理类
 */
@Slf4j
@SuppressWarnings("all")
public class AbstractSelector {

    /**
     * 校验Selector是否打开
     * @param selectorName
     * @return
     */
    public Selector getCheck(String selectorName) {
        Selector selector = NioServerContext.getSelector(selectorName);
        if(!Optional.ofNullable(selector).isPresent()) {
            throw new RuntimeException("can not get [ " + selectorName + " ] selector from cache");
        }

        if(!selector.isOpen()) {
            throw new RuntimeException("[ " + selectorName + " ] selector is not open");
        }

        return selector;
    }

    /**
     * 装饰EventModel类
     * @param eventModel
     * @param selectionKey
     */
    public void setEventModel(EventModel eventModel, SelectionKey selectionKey) {
        eventModel.setSelectionKey(selectionKey);

        // 客户端通道(包括上传或下载客户端通道)
        if(selectionKey.channel() instanceof SocketChannel) {
            SocketChannelContext socketChannelContext = (SocketChannelContext) selectionKey.attachment();
            eventModel.setRemoteAddress(socketChannelContext.getRemoteAddress()); // 远程地址
            if(socketChannelContext.getLocalAddress().contains(NioServerContext.getValue(BasicConstant.NIO_FILE_UPLOAD_PORT))) {
                eventModel.setEventModelEnum(EventModelEnum.FILE_UPLOAD_TASK);
            }
            if(socketChannelContext.getLocalAddress().contains(NioServerContext.getValue(BasicConstant.NIO_FILE_DOWNLOAD_PORT))) {
                eventModel.setEventModelEnum(EventModelEnum.FILE_DOWNLOAD_TASK);
            }
        }

        // 服务端通道
        if(selectionKey.channel() instanceof ServerSocketChannel) {
            eventModel.setLocalAddress(NioServerContext.getServerLocalAddress(((ServerSocketChannel) selectionKey.channel()))); // 本地地址
            // 根据本地对外暴露端口号判断是文件上传socket还是下载socket，以此来设置eventModel当前事件类型
            if(eventModel.getLocalAddress().contains(NioServerContext.getValue(BasicConstant.NIO_FILE_UPLOAD_PORT))) {
                eventModel.setEventModelEnum(EventModelEnum.FILE_UPLOAD_TASK);
            }

            if(eventModel.getLocalAddress().contains(NioServerContext.getValue(BasicConstant.NIO_FILE_DOWNLOAD_PORT))) {
                eventModel.setEventModelEnum(EventModelEnum.FILE_DOWNLOAD_TASK);
            }
        }

    }
}
