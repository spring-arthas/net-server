package com.alibaba.server.nio.service.file.handler;

import com.alibaba.fastjson.JSON;
import com.alibaba.server.common.BasicConstant;
import com.alibaba.server.nio.core.server.BasicServer;
import com.alibaba.server.nio.handler.event.AbstractEventHandler;
import com.alibaba.server.nio.handler.event.concret.WriteEventHandler;
import com.alibaba.server.nio.handler.pipe.ChannelContext;
import com.alibaba.server.nio.handler.pipe.standard.SimpleChannelContext;
import com.alibaba.server.nio.model.ChannelCacheDataModel;
import com.alibaba.server.nio.model.EventModel;
import com.alibaba.server.nio.model.SocketChannelContext;
import com.alibaba.server.nio.model.file.FileMessageFrame;
import com.alibaba.server.nio.repository.file.service.FileService;
import com.alibaba.server.nio.repository.file.service.param.FileCreateParam;
import com.alibaba.server.nio.repository.file.service.param.FileQueryParam;
import com.alibaba.server.nio.repository.user.service.UserService;
import com.alibaba.server.nio.repository.user.service.dto.UserDTO;
import com.alibaba.server.nio.repository.user.service.param.UserQueryParam;
import com.alibaba.server.nio.service.api.AbstractChannelHandler;
import com.alibaba.server.nio.service.file.util.FileMessageFrameParseUtil;
import com.alibaba.server.util.BasicUtil;
import com.alibaba.server.util.LocalTime;
import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang.StringUtils;
import org.springframework.util.CollectionUtils;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.channels.FileChannel;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 文件头原始数据解码处理器 --> 解码传送文件信息
 * @author spring
 * */

@Slf4j
@SuppressWarnings("all")
public class FileHeadDecodeHandler extends AbstractChannelHandler {

    /**
     * 由于加锁，当前handler同一时刻只能一个线程在运行, 记录当前正在处理的用户DTO
     * */
    private static UserDTO currentUserDto = null;

    /**
    * 全局查询用户类
    * */
    private static UserQueryParam currentUserQueryParam = new UserQueryParam();

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
            this.parseBytes(groupData.getBytes(), socketChannelContext, channelContext);
        }
    }

    /**
     * 解码字节数据
     * @param sumBytes  原始数据
     * @param socketChannelContext
     * @param channelContext
     * */
    private void parseBytes(byte[] sumBytes, SocketChannelContext socketChannelContext, ChannelContext channelContext) {
        // 是否开始读取具体数据
        boolean isBeginReadData = true, isRetainLastPacket = false;
        Integer i = 0;
        FileMessageFrame fileMessageFrame = null;
        while (i < sumBytes.length) {
            if(isBeginReadData) {
                // 1、每次进行新的帧解析需要判断剩余字节数据是否够一帧的基本数据,不够三个字节，则不进行处理，等待下次数据到来一并处理,如果符合该if，则一定是原始数据list最后一个包的数据，此时只要将该包的数据状态更新即可
                if(sumBytes.length - i < FileMessageFrame.UPLOAD_COMMON) {
                    isRetainLastPacket = true;
                    break;
                }

                if(sumBytes.length - i < FileMessageFrame.ONLINE_TRANSPORT_COMMON) {
                    isRetainLastPacket = true;
                    break;
                }

                // 2、解析文件帧基本数据后重置当前帧数据内容起始索引
                fileMessageFrame = new FileMessageFrame();
                // 3、解析完成基本的文件帧后，
                i = this.decodeFileFrameHandler(i, sumBytes, fileMessageFrame, channelContext, socketChannelContext);
                isBeginReadData = false;
            } else {
                // 4、校验帧基本数据是否完整
                if(!this.checkFileMessageFrame(i, fileMessageFrame)) {
                    if(fileMessageFrame.getFrameType().equals(FileMessageFrame.FrameType.ONLINE_TRANSPORT)) {
                        // 校验失败，未解析成功在线传输帧基本数据，则跳过，尝试继续解析下一帧
                        i = i + FileMessageFrame.ONLINE_TRANSPORT_COMMON;
                    }

                    if(fileMessageFrame.getFrameType().equals(FileMessageFrame.FrameType.UPLOAD)) {
                        i = i + FileMessageFrame.UPLOAD_COMMON;
                    }
                    continue;
                }

                // 5、读取文件名称长度数据并设置下一个索引位置，即需要开始解析新的帧
                i = this.parseData(i, sumBytes, fileMessageFrame);
                socketChannelContext.getTransportProtocol().getRealList().add(fileMessageFrame);
                isBeginReadData = true;
                continue;
            }
        }
    }

    /**
     * 解析文件帧基本数据
     *
     * @param i 从第几个字节开始处理
     * @param sumBytes 总共字节数
     * @param fileMessageFrame 当前数据帧
     * @param channelContext 当前handler所属处理器链上下文
     * @param socketChannelContext 当前通道SocketChannel附件
     * @return index 返回文件基本数据解析后的字节索引，即读取真实数据开始的索引位置
     */
    private int decodeFileFrameHandler(Integer i, byte[] sumBytes, FileMessageFrame fileMessageFrame, ChannelContext channelContext, SocketChannelContext socketChannelContext) {
        // 是否是结束帧 1B
        fileMessageFrame.setEndFrame(sumBytes[i]);

        // 帧序号 4B
        byte[] indexBytes = new byte[4];
        indexBytes[0] = sumBytes[i + 1];
        indexBytes[1] = sumBytes[i + 2];
        indexBytes[2] = sumBytes[i + 3];
        indexBytes[3] = sumBytes[i + 4];
        fileMessageFrame.setFrameIndex(BasicUtil.byteArrayToInt(indexBytes));

        // 解析帧类型、文件类型、文件操作类型 3B (三个字节)
        FileMessageFrameParseUtil.executeFileBasicTypeParse(sumBytes[i + 5], sumBytes[i + 6], sumBytes[i + 7], fileMessageFrame);

        // 此处需要根据帧类型做不同处理
        // 1、在线传输帧
        if(fileMessageFrame.getFrameType().equals(FileMessageFrame.FrameType.ONLINE_TRANSPORT)) {
            return FileMessageFrameParseUtil.executeUploadFrameParse(i, sumBytes, fileMessageFrame);
        }

        // 2、上传帧
        if(fileMessageFrame.getFrameType().equals(FileMessageFrame.FrameType.UPLOAD)) {
            return FileMessageFrameParseUtil.executeUploadFrameParse(i, sumBytes, fileMessageFrame);
        }

        // 3、下载帧
        if(fileMessageFrame.getFrameType().equals(FileMessageFrame.FrameType.DOWNLOAD)) {
            return FileMessageFrameParseUtil.executeDownloadFrameParse(i, sumBytes, fileMessageFrame);
        }

        // 4、删除帧
        if(fileMessageFrame.getFrameType().equals(FileMessageFrame.FrameType.DELETE)) {
            FileMessageFrameParseUtil.executeDeleteFrameParse(sumBytes, fileMessageFrame);
        }

        // 5、文件实时字节流数据传输帧 --> 即发送文件流实时数据  --> 此时的handler直接跳转至处理文件流转发handler
        if(fileMessageFrame.getFrameType().equals(FileMessageFrame.FrameType.DATA_TRANSPORT)) {
            Map<String, Object> map = new HashMap<>();

            // 5.1、 解析实时文件流数据帧
            FileMessageFrameParseUtil.executeDataTransportFrameParse(i, sumBytes, fileMessageFrame);

            // 5.2、拷贝当前帧中的文件流数据 其中 10 包含 ： 3个字节的(帧类型，文件类型，操作类型数据)， 2个字节的数据内容长度字节(即short), 4个字节的包序号 1个字节的是否为结束帧
            byte[] orginFileStreamData = new byte[sumBytes.length - (10 + fileMessageFrame.getFrameSumLength())];
            System.arraycopy(sumBytes, (10 + fileMessageFrame.getFrameSumLength()), orginFileStreamData, 0, orginFileStreamData.length);

            // 5.3、根据fileTaskTag获取当前用户文件上传任务
            String currentUserName = fileMessageFrame.getData().split(",")[0];
            String fileTaskTag = fileMessageFrame.getData().split(",")[1];
            Map<String, Object> fileTaskMap = null;
            if(null == currentUserDto || !StringUtils.equals(currentUserDto.getUserName(), currentUserName)) {
                currentUserQueryParam.setUserName(currentUserName);
                currentUserDto = AbstractChannelHandler.userService.getOnlineUser(currentUserQueryParam);
            }

            // 5.4、封装文件上传当前片段任务，并跳转至文件流传输handler向接收端用户发送文件流数据
            // 文件流数据
            map.put(BasicConstant.ORIGIN_FILE_STREAM_DATA, orginFileStreamData);
            map.put(BasicConstant.FILE_FRAME, fileMessageFrame);
            map.put(BasicConstant.FILE_TASK, currentUserDto.getUploadFileMap().get(fileTaskTag));

            SimpleChannelContext simpleChannelContext = (SimpleChannelContext) channelContext;
            if(!Optional.ofNullable(simpleChannelContext.getObj()).isPresent()) {
                List<Map<String, Object>> list = Lists.newArrayList();
                list.add(map);
                simpleChannelContext.setObj(list);
            } else {
                ((List<Map<String, Object>>) simpleChannelContext.getObj()).add(map);
            }
            simpleChannelContext.setNeedSkip(Boolean.TRUE).setSkip(3); // --> 跳转至FileOnlineTransportStreamHandler

            // 返回文件流字节数据末尾，不再进行后续帧数据解析
            return i = sumBytes.length;
        }

        // 6、实时文件数据传输结束帧(关闭发起端和接收端两个用户关联的FileSocektChannel)
        if(fileMessageFrame.getFrameType().equals(FileMessageFrame.FrameType.DATA_TRANSPORT_END)) {
            // 6.1、 解析实时文件流数据帧
            FileMessageFrameParseUtil.executeDataTransportEndFrameParse(i, sumBytes, fileMessageFrame);

            // 6.2、解析数据
            Map<String, String> map = (Map<String, String>) JSON.parseObject(fileMessageFrame.getData(), Map.class);

            // 6.3、插入文件记录数据
            FileQueryParam fileQueryParam = new FileQueryParam();
            fileQueryParam.setPId(Long.valueOf(map.get("PID")));
            fileQueryParam.setUserName(map.get(BasicConstant.CLOSE_USER));
            fileQueryParam.setFileName(map.get("FILE.NAME"));
            fileQueryParam.setFileSize(Long.valueOf(map.get("FILE.SIZE")));
            fileQueryParam.setFilePath(map.get("FILE.PATH").toString() + fileQueryParam.getFileName());
            fileQueryParam.setFileType(map.get("FILE.TYPE"));
            FileService fileService = BasicServer.classPathXmlApplicationContext.getBean(FileService.class);
            fileService.createFile(fileQueryParam);

            // 6.4、通知发起端关闭文件socketChannel
            /*Map<String, Object> callBackMessageMap = new HashMap<>();
            callBackMessageMap.put("status", map.get(BasicConstant.FILE_STREAM_SEND_END));
            callBackMessageMap.put("code", 200);
            callBackMessageMap.put("frameType", FileMessageFrame.FrameType.DATA_TRANSPORT_END.getBit());
            callBackMessageMap.put("time", BasicConstant.SDF.format(new Date()));
            WriteEventHandler.addSendData(callBackMessageMap, socketChannelContext);*/

            // 6.5、清除当前发起用户UserDTO占用的文件FileSocketChannel, help GC
            String userName = map.get(BasicConstant.CLOSE_USER);
            UserQueryParam userQueryParam = new UserQueryParam();
            userQueryParam.setUserName(userName);
            UserDTO userDto = BasicServer.classPathXmlApplicationContext.getBean(UserService.class).getOnlineUser(userQueryParam);
            userDto.setFileSocketChannel(null);
            if(!CollectionUtils.isEmpty(userDto.getUploadFileMap()) && userDto.getUploadFileMap().containsKey(map.get("TAG").toString())) {
                // 上传成功，释放待上传文件占用资源,即删除userDto中map对应的key-value
                Map<String, Object> fileMap = userDto.getUploadFileMap().remove(map.get("TAG").toString());
                FileChannel fileChannel = (FileChannel) fileMap.get("FILE_CHANNEL");
                if(fileChannel != null && fileChannel.isOpen()) {
                    try {
                        fileChannel.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }

            // 6.6、移除当前socketChannel通道对应的缓存数据
            ChannelCacheDataModel channelCacheDataModel = AbstractEventHandler.channelDataMap.get(socketChannelContext.getRemoteAddress());
            AbstractEventHandler.channelDataMap.remove(socketChannelContext.getRemoteAddress(), channelCacheDataModel);
            log.info("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] FileHeadDecodeHandler | --> 当前用户 [{}] 已完成在线文件传输或上传, 已关闭相应文件通道以及socketChannel, thread = {}", userName, Thread.currentThread().getName());

            // 6.7、终止当前Handler
            ((SimpleChannelContext) channelContext).setNeedStop(Boolean.TRUE);
            return i = sumBytes.length;
        }

        return 0;
    }

    /**
     * 校验帧是否完整
     * @param i 从第几个字节开始处理
     * @param fileMessageFrame
     * @para return
     */
    private Boolean checkFileMessageFrame(Integer i, FileMessageFrame fileMessageFrame) {
        // 在线传输帧校验
        if(fileMessageFrame.getFrameType().equals(FileMessageFrame.FrameType.ONLINE_TRANSPORT)) {
            if(/*null == fileMessageFrame.getFrameSumLength()
                    ||*/ null == fileMessageFrame.getFrameType()
                    || null == fileMessageFrame.getFileOperateType()
                    || null == fileMessageFrame.getFileType()) {
                return Boolean.FALSE;
            }
        }

        // 上传帧
        if(fileMessageFrame.getFrameType().equals(FileMessageFrame.FrameType.UPLOAD)) {
            if(/*null == fileMessageFrame.getFrameSumLength()
                    ||*/ null == fileMessageFrame.getFrameType()
                    || null == fileMessageFrame.getFileOperateType()
                    || null == fileMessageFrame.getFileType()
                    || null == fileMessageFrame.getDataLength()
                    || null == fileMessageFrame.getFileLength()) {
                return Boolean.FALSE;
            }

            if(/*0 == fileMessageFrame.getFrameSumLength()
                    ||*/ 0 == fileMessageFrame.getDataLength()
                    || 0 == fileMessageFrame.getFileLength()) {
                return Boolean.FALSE;
            }
        }

        return Boolean.TRUE;
    }

    /**
     * 读取数据
     * @param i
     * @param sumBytes
     * @param fileMessageFrame
     * @return index 返回文件真实数据解析后的字节索引
     */
    private int parseData(Integer i, byte[] sumBytes, FileMessageFrame fileMessageFrame) {
        byte[] content = null;

        // 在线传输帧
        if(fileMessageFrame.getFrameType().equals(FileMessageFrame.FrameType.ONLINE_TRANSPORT)) {
            content = new byte[fileMessageFrame.getDataLength()];
        }

        // 上传帧
        if(fileMessageFrame.getFrameType().equals(FileMessageFrame.FrameType.UPLOAD)) {
            content = new byte[fileMessageFrame.getDataLength()];
        }

        // 下载帧
        if(fileMessageFrame.getFrameType().equals(FileMessageFrame.FrameType.DOWNLOAD)) {
            content = new byte[fileMessageFrame.getDataLength()];
        }

        // 删除帧
        if(fileMessageFrame.getFrameType().equals(FileMessageFrame.FrameType.DELETE)) {
            content = new byte[fileMessageFrame.getDataLength()];
        }

        int k = 0;
        for(int j = i; j < (i + content.length); j++) {
            content[k] = sumBytes[j];
            k++;
        }

        // 5、解析原始数据
        try {
            fileMessageFrame.setData(new String(content, "UTF-8"));
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }

        return i + content.length;
    }
}
