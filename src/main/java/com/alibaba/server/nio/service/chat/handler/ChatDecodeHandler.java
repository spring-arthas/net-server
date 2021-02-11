package com.alibaba.server.nio.service.chat.handler;

import com.alibaba.server.common.BasicConstant;
import com.alibaba.server.nio.core.server.BasicServer;
import com.alibaba.server.nio.handler.pipe.ChannelContext;
import com.alibaba.server.nio.model.EventModel;
import com.alibaba.server.nio.model.SocketChannelContext;
import com.alibaba.server.nio.model.chat.ChatMessageFrame;
import com.alibaba.server.nio.model.chat.ChatMessageFrame.FrameType;
import com.alibaba.server.nio.service.api.AbstractChannelHandler;
import com.alibaba.server.util.BasicUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.*;

/**
 * 聊天读事件原始数据解码处理器
 *
 * @author spring
 * */

@Slf4j
@SuppressWarnings("all")
public class ChatDecodeHandler extends AbstractChannelHandler {

    @Override
    public void handler(Object o, ChannelContext channelContext) throws IOException {
        Map<String, Object> map = (Map<String, Object>) o;
        SocketChannelContext socketChannelContext = (SocketChannelContext) map.get("SOCKET_CHANNEL_CONTEXT");
        List<EventModel.GroupData> completeList = (List<EventModel.GroupData>) map.get("COMPLETE_LIST");
        if(CollectionUtils.isEmpty(completeList)) {
            return;
        }

        // 解析数据
        for(EventModel.GroupData groupData : completeList) {
            this.parseBytes(groupData, socketChannelContext, channelContext);
        }
    }

    /**
     * 解码字节数据
     * @param groupData  原始数据对象
     * @param socketChannelContext
     * @param channelContext
     * */
    private void parseBytes(EventModel.GroupData groupData, SocketChannelContext socketChannelContext, ChannelContext channelContext) {
        // 是否开始读取具体数据
        boolean isBeginReadData = true, isRetainLastPacket = false;
        int i = 0;
        ChatMessageFrame chatMessageFrame = null;
        while (i < groupData.getLength()) {
            if(isBeginReadData) {
                // 每次进行新的帧解析需要判断剩余字节数据是否够一帧的基本数据,不够三个字节，则不进行处理，等待下次数据到来一并处理,如果符合该if，则一定是原始数据list最后一个包的数据，此时只要将该包的数据状态更新即可
                if(groupData.getLength() - i < ChatMessageFrame.COMMON) {
                    isRetainLastPacket = true;
                    break;
                }

                chatMessageFrame = new ChatMessageFrame();
                i = this.parseFrameBasicInfo(i, groupData, chatMessageFrame);
                isBeginReadData = false;
            } else {
                // 判断帧类型是否为空或需要读取的真实数据长度是否为
                if(chatMessageFrame.getFrameType() == null || chatMessageFrame.getFrameLength() == null || chatMessageFrame.getFrameLength() == 0) {
                    // 有可能存在读取某帧时出现三个字节中在解析帧类型或是帧长度出现问题，此处导致死循环，释放不了锁，解决方案为逃过当前三个字节，尝试解析下一个帧的三个字节,从而辅助跳出while循环
                    i = i + 3;
                    isBeginReadData = true;
                    continue;
                }

                // 读取相应长度的数据
                byte[] content = new byte[chatMessageFrame.getFrameLength()];
                int k = 0;
                for(int j = i; j < (i + content.length); j++) {
                    content[k] = groupData.getBytes()[j];
                    k++;
                }

                // 解析原始数据
                try {
                    chatMessageFrame.setData(new String(content, "UTF-8"));
                } catch (UnsupportedEncodingException e) {
                    e.printStackTrace();
                }
                socketChannelContext.getTransportProtocol().getRealList().add(chatMessageFrame);

                // 设置下一个新数据帧读取索引位置，即需要开始解析新的帧
                i = i + content.length;
                isBeginReadData = true;
                continue;
            }
        }
    }

    /**
     * @param k 当前字节数据解析索引位置
     * @param groupData
     * @param chatMessageFrame 当前帧数据
     * @return
     */
    private Integer parseFrameBasicInfo(Integer k, EventModel.GroupData groupData, ChatMessageFrame chatMessageFrame) {
        // 是否是结束帧
        chatMessageFrame.setEndFrame(groupData.getBytes()[k]);
        // 解析帧类型
        this.getFrameType(groupData.getBytes()[k + 5], chatMessageFrame);
        // 解析帧索引
        chatMessageFrame.setIndex(groupData.getIndex());
        // 解析帧长度
        this.getFrameLength(groupData.getBytes()[k + 6], groupData.getBytes()[k + 7], chatMessageFrame);

        return k = k + 8;
    }

    /**
     * 解析帧类型
     * @param b
     * @param chatMessageFrame
     * @return
     * */
    private void getFrameType(byte b, ChatMessageFrame chatMessageFrame) {
        StringBuilder stringBuilder = new StringBuilder(Integer.toBinaryString(b));
        while (stringBuilder.toString().length() != 8) {
            stringBuilder.insert(0, "0");
        }

        ChatMessageFrame.FrameType frameType = ((Map<String, ChatMessageFrame.FrameType>) BasicServer.getMap().get(BasicConstant.CHAT_MESSAGE_FRAME_TYPE)).get(stringBuilder.toString());
        chatMessageFrame.setFrameType(frameType);
    }

    /**
     * 解析帧长度
     *
     * @param b1
     * @param b2
     * @param chatMessageFrame
     *
     * @return
     * */
    private void getFrameLength(byte b1, byte b2, ChatMessageFrame chatMessageFrame) {
        byte[] shortBytes = new byte[2];
        shortBytes[0] = b1;
        shortBytes[1] = b2;
        Short frameLength = BasicUtil.byte2short(shortBytes);
        chatMessageFrame.setFrameLength(frameLength);
    }

}
