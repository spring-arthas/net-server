package com.alibaba.server.nio.service.file.handler;

import com.alibaba.server.common.BasicConstant;
import com.alibaba.server.nio.core.server.NioServerContext;
import com.alibaba.server.nio.handler.pipe.ChannelContext;
import com.alibaba.server.nio.handler.pipe.standard.SimpleChannelContext;
import com.alibaba.server.nio.model.SocketChannelContext;
import com.alibaba.server.nio.model.file.FileMessageFrame;
import com.alibaba.server.nio.service.api.AbstractChannelHandler;
import com.alibaba.server.util.LocalTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 文件上传或在线传输实时流处理
 * @author spring
 * */

@Slf4j
@SuppressWarnings("all")
public class FileUploadTransportStreamHandler extends AbstractChannelHandler {

    private FileChannel fileChannel = null;

    private Long writeFileSize = 0L;

    private String fileName = "";

    @Override
    public void handler(Object o, ChannelContext channelContext) throws IOException {
        Map<String, Object> map = (Map<String, Object>) o;
        SocketChannelContext socketChannelContext = (SocketChannelContext) map.get("SOCKET_CHANNEL_CONTEXT");
        List<Map<String, Object>> list = (List<Map<String, Object>>) ((SimpleChannelContext) channelContext).getObj();
        if(!CollectionUtils.isEmpty(list)) {
            // 按照帧序号排序,升序排序，此处的顺序必须为1~...,且序号必须连续相差1，否则文件流顺序写入错误，终止写入
            if(!this.sortStreamList(list)) {
                NioServerContext.closedAndRelease(socketChannelContext.getTransportProtocol().getSocketChannel());
                fileChannel.close();
                log.info("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] FileUploadTransportStreamHandler | --> 文件 [{}] 在流写入过程出现流索引差值不连续(相差结果不为1)，已终止文件流写入 thread = {}",
                    fileName, Thread.currentThread().getName());
                return;
            }

            for(Map<String, Object> fileTaskMap : list) {
                Object obj = null;
                if(!Optional.ofNullable(obj = fileTaskMap.get(BasicConstant.FILE_TASK)).isPresent()) {
                    continue;
                }

                this.writeBytes(obj, fileTaskMap, socketChannelContext);
            }

            // 发送完成后清空文件流list数据,如果不清空下次发送回重复进行发送
            list.clear();
        }
    }

    /**
     * 写入发送过来的文件流数据
     * @param obj
     * @param fileTaskMap
     * @param socketChannelContext
     * @throws IOException
     */
    private void writeBytes(Object obj, Map<String, Object> fileTaskMap, SocketChannelContext socketChannelContext) throws IOException {
        // 文件任务
        Map<String, Object> currentFileTaskMap = (Map<String, Object>) obj;

        // 文件帧
        FileMessageFrame fileMessageFrame = (FileMessageFrame) fileTaskMap.get(BasicConstant.FILE_FRAME);

        // 原始文件字节流数据
        byte[] originFileStreamData = (byte[]) fileTaskMap.get(BasicConstant.ORIGIN_FILE_STREAM_DATA);

        fileName = ((File) currentFileTaskMap.get("FILE")).getName();
        fileChannel = (FileChannel) currentFileTaskMap.get("FILE_CHANNEL");
        if(!fileChannel.isOpen()) {
            // 文件通道未打开，写入失败，释放资源
            NioServerContext.closedAndRelease(socketChannelContext.getTransportProtocol().getSocketChannel());
            fileChannel.close();
            log.info("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] FileUploadTransportStreamHandler | --> 文件 [{}] 通道未打开，已终止文件流写入, thread = {}",
                fileName, Thread.currentThread().getName());
        }

        // 向文件通道写入数据
        this.writeFileSize += fileChannel.write(ByteBuffer.wrap(originFileStreamData, 0, originFileStreamData.length));
        log.info("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] FileUploadTransportStreamHandler | --> 当前线程 [{}] 完成文件 [{} - {}] 个字节流上传", Thread.currentThread().getName(), fileName, this.writeFileSize);
    }

    /**
     * 按照帧序号排序,升序排序，此处的顺序必须为1~...
     * @param list
     */
    private Boolean sortStreamList(List<Map<String, Object>> list) {
        if(list.size() == 1) {
            return Boolean.TRUE;
        }

        // 先自增排序
        //list.stream().sorted(Comparator.comparing(EmployeePayrollProfileDTO::getEffectiveStartDate).reversed())
        Collections.sort(list, new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> m1, Map<String, Object> m2) {
                Integer index1 = ((FileMessageFrame) m1.get(BasicConstant.FILE_FRAME)).getFrameIndex();
                Integer index2 = ((FileMessageFrame) m2.get(BasicConstant.FILE_FRAME)).getFrameIndex();
                return index1.compareTo(index2);
            }
        });

        // 在判断元素间的差值是否为1
        for(int i = 0; i < (list.size() - 1); i++) {
            Integer index1 = ((FileMessageFrame) list.get(i).get(BasicConstant.FILE_FRAME)).getFrameIndex();
            Integer index2 = ((FileMessageFrame) list.get(i + 1).get(BasicConstant.FILE_FRAME)).getFrameIndex();
            if(!((index2 - index1) == 1)) {
                System.out.println("差值不为1，发送失败, 当前list中的帧序号为: " + index1 + "~" + index2);
                return Boolean.FALSE;
            }
        }

        return Boolean.TRUE;
    }
}
