package com.alibaba.server.nio.handler.event.concret;

import com.alibaba.server.common.BasicConstant;
import com.alibaba.server.nio.core.server.NioServerContext;
import com.alibaba.server.nio.handler.event.AbstractEventHandler;
import com.alibaba.server.nio.handler.worker.WorkerThreadPool;
import com.alibaba.server.nio.model.ChannelCacheDataModel;
import com.alibaba.server.nio.model.EventModel;
import com.alibaba.server.nio.model.SocketChannelContext;
import com.alibaba.server.nio.model.constant.EventModelEnum;
import com.alibaba.server.nio.reactor.GlobalMainReactor;
import com.alibaba.server.nio.reactor.SubReactor;
import com.alibaba.server.util.BasicUtil;
import com.alibaba.server.util.ByteOrderConvert;
import com.alibaba.server.util.LocalTime;
import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;
import java.time.LocalDateTime;
import java.util.*;

/**
 * @Auther: YSFY
 * @Date: 2020-11-21 12:07
 * @Pacage_name: com.alibaba.server.nio.Handler.concret
 * @Project_Name: net-server
 * @Description: 读事件处理[ReadEventHandler]
 */
@Slf4j
@SuppressWarnings("all")
public class ReadEventHandler extends AbstractEventHandler {
    private static Integer chatIndex = 0, fileIndex = 0;

    @Override
    public EventModel eventHandler(EventModel eventModel) {
        if(!super.checkEvent(eventModel)) {
            return eventModel;
        }

        if(!eventModel.getSelectionKey().isReadable()) {
            if(!Optional.ofNullable(super.getNextEventHandler()).isPresent()) {
                return eventModel;
            }
            return super.getNextEventHandler().eventHandler(eventModel);
        }

        return this.readHandler(eventModel);
    }

    /**
     * 执行处理,提交线程池处理读事件,解决粘包半包问题后，提交数据至线程池处理
     * @param eventModel
     * @return eventModel
     * */
    private EventModel readHandler(EventModel eventModel) {
        // 1、由于nio是事件水平触发，即当内核读缓冲区可读或是可写方可触发selector唤醒，产生读写事件触发，即在一次Selectionkey处理
        Boolean result = this.readData(eventModel, this.cacheDataHandler(eventModel), eventModel.getRemoteAddress());
        if(!result) {
            return eventModel;
        }

        // 2、聊天业务处理
        if(eventModel.getEventModelEnum().equals(EventModelEnum.CHAT_TASK) && result) {
            eventModel.setIndex((chatIndex > Integer.MAX_VALUE) ? 0 : chatIndex++);

            // 3、获取当前通道对应的subReactor线程
            SubReactor reactor = GlobalMainReactor.getSubReactorForSocketChannel((SocketChannel) eventModel.getSelectionKey().channel());
            if(!Optional.ofNullable(reactor).isPresent()) {
                // 为空，表示没有找到对应通道的Subreacto线程，有可能为之前用户下线导致清除了用户对应的SubReactor线程，但是socketChannel并未断开，故此时重新建立新的Subreactor即可
                this.registerSubReactor(eventModel, BasicConstant.NIO_SERVER_MAIN_CORE_CHAT_SELECTOR);
                return eventModel;
            }

            // 4、将当前通道触发的读事件数据 --> 加入当前SocketChannel连接对应的的Subreactor线程数据处理队列, 可用空间必须大于0
            if(reactor.getQueue().remainingCapacity() > 0) {
                Map<String, Object> queueMap = new HashMap<>();
                queueMap.put("SOCKET_CHANNEL_CONTEXT",eventModel.getSelectionKey().attachment());
                queueMap.put("COMPLETE_LIST", eventModel.getCompleteList());
                reactor.getQueue().offer(queueMap);
                return eventModel;
            }

            log.warn("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] ReadEventHandler | --> chat socketChannel read cache queue is Full, avilableCount = {}, address = {}, thread = {}",
                reactor.getQueue().remainingCapacity(), eventModel.getRemoteAddress(), Thread.currentThread().getName());

            return eventModel;
        }

        // 3、文件业务
        //eventModel.setIndex((fileIndex > Integer.MAX_VALUE) ? 0 : fileIndex++);
        Map<String, Object> queueMap = new HashMap<>();
        queueMap.put("SOCKET_CHANNEL_CONTEXT",eventModel.getSelectionKey().attachment());
        queueMap.put("COMPLETE_LIST", eventModel.getCompleteList());
        WorkerThreadPool.submit(queueMap, eventModel.getEventModelEnum().getName());
        return eventModel;
    }

    /**
     * 从通道读取数据,此处解决粘包和半包问题, 将cacheDataModel中的数据进行包处理，处理完成后放入eventMode中的completeList中，并清空cacheDataModel处理完成的数据
     * @param eventModel  当前的通道事件数据
     * @param channelCacheDataModel 当前通道缓存数据
     * @param currendAddress 当前通道名称(ip:port)
     * @return
     * */
    private Boolean readData(EventModel eventModel, ChannelCacheDataModel channelCacheDataModel, String currendAddress) {
        SocketChannel socketChannel = (SocketChannel) eventModel.getSelectionKey().channel();
        SocketChannelContext socketChannelContext = (SocketChannelContext) eventModel.getSelectionKey().attachment();

        // 1、读取数据
        ByteBuffer byteBuffer = socketChannelContext.getByteBuffer();
        byteBuffer.clear();
        int readBytes = 0;
        try {
            while ((readBytes = socketChannel.read(byteBuffer)) > 0 ) {
                // 每读取一次做为一个GroupData进行封装，但是每read一次的大小不一定按照byteBuffer指定大小进行读取
                byteBuffer.flip();
                if (byteBuffer.hasRemaining()) {
                    byte[] bytes = new byte[byteBuffer.remaining()];
                    // 按照发送的次序依次从byteBuffer读取进bytes数组
                    byteBuffer.get(bytes);

                    // 将当前数据进行封装,没读取一次执行一次处理
                    EventModel.GroupData groupData = eventModel.new GroupData();
                    groupData.setLength(bytes.length);
                    groupData.setIndex(channelCacheDataModel.getIndex());
                    groupData.setBytes(bytes);
                    groupData.setStatus("UN_HANDLE");
                    channelCacheDataModel.setIndex(groupData.getIndex() + 1);

                    // 验证及处理当前帧
                    this.verificateHandleCurrentGroupData(channelCacheDataModel, groupData, eventModel);

                    byteBuffer.clear();
                }
            }

            // 客户端关闭输入输出流或直接调用close()会读取到-1
            if(readBytes == -1) {
                // chat服务与File服务公用一个ReadEventHandler,此处需要判断当前socketChannel对应的服务是文件服务还是聊天服务，如果是文件服务需要删除掉上传失败的文件
                eventModel.getSelectionKey().cancel();
                NioServerContext.closedAndRelease(socketChannel);
                return Boolean.FALSE;
            }
        } catch (IOException e) {
            // 处理read产生的异常
            if(e instanceof SocketException) {
                // Connection reset || Connection reset by peer:Socket write error || Broken pipe
            }

            if(e instanceof ClosedChannelException) { // socketChanel 已经关闭依旧发生read
                log.warn("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] ReadEventHandler | --> can not read data at a closed socketChannel}");
            }

            if(e instanceof IOException) {
                e.printStackTrace();
            }

            NioServerContext.closedAndRelease(socketChannel);
            return Boolean.FALSE;
        }

        // 2、读取的缓存数据为空，则直接返回
        /*if(CollectionUtils.isEmpty(channelCacheDataModel.getList())) {
            return Boolean.FALSE;
        }*/

        /*log.info("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] ReadEventHandler | --> 当前通道 [{}] 已读取数据字节数为 [{}], 开始进行粘包半包字节处理",
            currendAddress, cacheDataModel.getList().stream().mapToInt(EventModel.GroupData::getLength).sum());*/

        // 3、粘包半包处理  --> 处理前排序 --> 按照接收的先后顺序进行排序
        //this.stickingAndAalfWrapping(channelCacheDataModel.getList(), Lists.newArrayList(), eventModel);
        //log.info("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] ReadEventHandler | --> 当前通道 [{}] 粘包半包处理完成, 开始执行字节数据解析处理判断", currendAddress);

        // 4、判断当前数据能否处理多少，能达到多少处理标准就处理多少数据
        if(judgeIsCouldExecuteHandle(eventModel, channelCacheDataModel, currendAddress)) {
            return Boolean.TRUE;
        }
        return Boolean.FALSE;
    }

    /**
     * 验证及处理当前帧
     * @param currentChannelCacheDataModel 当前通道缓存对象
     * @param currentGroupData 当前通道待处理缓存数据
     * @param currentEventModel 当前通道事件数据模型
     *      基本位数据: 2 + 1 + 4 + 3 + [动态大小] = 10 + [动态大小]
     *          帧组成: 2B : 当前帧总长度
     *                 1B : 当前帧是否是结束帧
     *                 4B : 当前帧发送序号(由客户端确定)
     *                 3B : 帧基本数据 [1B : 帧类型, 1B : 文件类型, 1B : 文件操作类型]
     *            动态大小 : [4B + 不确定大小]，其中4B为动态数据长度大小(int 4字节)
     */
    private void verificateHandleCurrentGroupData(ChannelCacheDataModel currentChannelCacheDataModel, EventModel.GroupData currentGroupData, EventModel currentEventModel) {
        // 1、判断当前数据长度是否能够处理，即校验基本位字节段 int orginStreamDataIndex = 2 + 1 + 4 + 3 + sendDataLengthBytes.Length + sendDataBytes.Length;
        if(!CollectionUtils.isEmpty(currentChannelCacheDataModel.getList())) {
            List<EventModel.GroupData> cacheList = currentChannelCacheDataModel.getList();
            // 先将新读入得数据追加至缓存至最后一个
            cacheList.add(currentGroupData);
            // 当前通道缓存数据不为空，需要先处理缓存数据，在处理当前缓存数据，处理缓存数据前先排序(升序排序)
            cacheList.stream().sorted(Comparator.comparing(EventModel.GroupData::getIndex));

            //

            // 1.1、处理缓存和当前帧数据
            int currentIndex = 0;
            while (currentIndex < cacheList.size()) {
                int nextIndex = this.executeParseCurrentGroupData(currentChannelCacheDataModel, cacheList.get(currentIndex), currentIndex, currentEventModel);
                if(nextIndex == -1) {
                    //cacheList.remove(currentIndex);
                    return;
                }

                // 索引越界，则无法处理
                if(nextIndex >= cacheList.size()) {
                    return;
                }

                // 判断当前索引指示的GroupData是否处于处理完成状态，如果是则continue，如果不是则继续处理
                EventModel.GroupData nextIndexGroupData = cacheList.get(nextIndex);
                if(StringUtils.equals(nextIndexGroupData.getStatus(), "UN_HANDLE")) {
                    // 设置当前索引为返回索引，表明当前返回的索引指示的帧缓存数据一部分为上一帧，一部分为下一帧，所以此处需要再次处理，那么下次循环会对当前索引
                    // 表示的GroupData再次进行处理，此时处理的为后半部分为下一帧的数据
                    currentIndex = nextIndex;
                    continue;
                }

                currentIndex = nextIndex + 1;
            }
        } else {
            // 2、没有缓存数据，直接处理当前缓存数据
            if(currentGroupData.getLength() == 0) {
                return;
            }

            // 3、当前缓存数据以基本位数据长度为判断依据，如果小于基本位长度数据，直接进行缓存，不进行处理(即小于14个byte)
            if(currentGroupData.getLength() < 14) {
                currentChannelCacheDataModel.getList().add(currentGroupData);
                return;
            }

            // 4、处理当前帧缓存数据
            currentChannelCacheDataModel.getList().add(currentGroupData);
            int nextIndex = this.executeParseCurrentGroupData(currentChannelCacheDataModel, currentGroupData, 0, currentEventModel);
            if(nextIndex == -1) {
                //currentChannelCacheDataModel.getList().remove(0);
                return;
            }
        }
    }

    /**
     * 处理当前数据，此时当前帧数据必定大于14
     * @param currentChannelCacheDataModel 当前通道缓存对象
     * @param currentGroupData 当前通道待处理缓存数据
     * @param currentIndex 当前groupData对应在通道缓存对象中的索引
     * @param currentEventModel 当前通道事件数据模型
     */
    private int executeParseCurrentGroupData(ChannelCacheDataModel currentChannelCacheDataModel, EventModel.GroupData currentGroupData, int currentIndex, EventModel currentEventModel) {
        int currentGroupDataLength = currentGroupData.getLength();

        // 1、解析当前帧总长度
        int currentFrameSumLength = this.getFrameSumLengthBytes(currentGroupData.getBytes()[0], currentGroupData.getBytes()[1]);
        if(currentFrameSumLength <= 0) {
            log.info("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] ReadEventHandler | --> 通道 [{}] 解析当前帧 [序号 = {}] 的帧总长度数据错误, 解析出来的帧总长度 = [{}]",
                currentEventModel.getRemoteAddress(), currentGroupData.getIndex(), currentFrameSumLength);
            return -1;
        }

        // 2、如果当前帧指定的总长度(currentFrameSumLength) = 当前帧缓存数据长度  -->  刚好能够处理完整帧
        if(currentFrameSumLength == currentGroupDataLength) {
            addCompleteFrameData(currentChannelCacheDataModel, currentGroupData, currentIndex, currentFrameSumLength, currentEventModel);
            return currentIndex;
        }

        // 3、如果当前帧指定的总长度(currentFrameSumLength) > 当前帧缓存数据长度  -->  完整帧的数据需要跨越多个groupData
        if(currentFrameSumLength > currentGroupDataLength) {
            try {
                return parseFrameSumLengthBiggerThanCurrentGroupDataLength(currentChannelCacheDataModel, currentGroupData, currentIndex, currentFrameSumLength, currentEventModel);
            } catch (ArrayIndexOutOfBoundsException e) {
                log.info("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] ReadEventHandler | --> 处理通道 [{}] 字节数据出现ArrayIndexOutOfBoundsException", currentEventModel.getRemoteAddress());
            }
        }

        // 4、如果当前帧指定的总长度(currentFrameSumLength) > 当前帧缓存数据长度  -->  完整帧在当前帧中处理完还存在剩余
        if(currentFrameSumLength < currentGroupDataLength) {
            try {
                return parseFrameSumLengthSmallerThanCurrentGroupDataLength(currentChannelCacheDataModel, currentGroupData, currentIndex, currentFrameSumLength, currentEventModel);
            } catch (ArrayIndexOutOfBoundsException e) {
                log.info("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] ReadEventHandler | --> 处理通道 [{}] 字节数据出现ArrayIndexOutOfBoundsException", currentEventModel.getRemoteAddress());
            }
        }

        return -1;
    }

    /**
     * 当前帧总长度数据大于当前通道缓存数据处理逻辑
     * @param currentChannelCacheDataModel 当前通道缓存对象
     * @param currentGroupData 当前通道待处理缓存数据
     * @param currentIndex 当前groupData对应在通道缓存对象中的索引
     * @param currentFrameSumLength 当前通道待处理缓存数据解析出的帧总长度
     * @param currentEventModel 当前通道事件数据模型
     */
    private int parseFrameSumLengthBiggerThanCurrentGroupDataLength(ChannelCacheDataModel currentChannelCacheDataModel,
         EventModel.GroupData currentGroupData, int currentIndex, int currentFrameSumLength, EventModel currentEventModel) {
        // 先处理当前通道数据，构建新的
        EventModel.GroupData newCompleteGroupData = currentEventModel.new GroupData();
        byte[] newCompleteGroupBytes = new byte[currentFrameSumLength - 2];
        System.arraycopy(currentGroupData.getBytes(), 2, newCompleteGroupBytes, 0, (currentGroupData.getLength() - 2));
        newCompleteGroupData.setLength(currentGroupData.getLength() - 2);
        newCompleteGroupData.setEndFrame(newCompleteGroupBytes[0]);
        byte[] indexBytes = new byte[4];
        indexBytes[0] = newCompleteGroupBytes[1];
        indexBytes[1] = newCompleteGroupBytes[2];
        indexBytes[2] = newCompleteGroupBytes[3];
        indexBytes[3] = newCompleteGroupBytes[4];
        newCompleteGroupData.setIndex(BasicUtil.byteArrayToInt(indexBytes));

        // 提前从当前通道缓存中根据当前索引获取下一份数据
        int nextIndex = currentIndex + 1;
        EventModel.GroupData nextGroupData = null;
        while (true) {
            // 下一个帧缓存数据索引,并判断下一帧缓存数据是否存在，不存在则不处理
            if(nextIndex >= currentChannelCacheDataModel.getList().size()) {
                // 此处如果循环过程出现索引越界，那说明当前帧都没有处理完，虽说跨过几帧，但是都是白处理的，所以此处还是需要将当前处理的GroupData设置为未处理后返回
                currentChannelCacheDataModel.getList().stream().forEach(groupData -> groupData.setStatus("UN_HANDLE"));
                return nextIndex;
            }

            nextGroupData = currentChannelCacheDataModel.getList().get(nextIndex);
            int nextGroupDataLenth = nextGroupData.getLength();
            if((nextGroupDataLenth + newCompleteGroupData.getLength()) == (currentFrameSumLength - 2)) {
                System.arraycopy(nextGroupData.getBytes(), 0, newCompleteGroupBytes, newCompleteGroupData.getLength(), nextGroupDataLenth);
                newCompleteGroupData.setLength(newCompleteGroupData.getLength() + nextGroupDataLenth);
                newCompleteGroupData.setBytes(newCompleteGroupBytes);
                nextGroupData.setStatus("HANDLE_SUCCESS");
                currentEventModel.getCompleteList().add(newCompleteGroupData);
                break;
            }

            // 说明当前索引对应的帧数据存在跨越，即下一帧依旧未能获取到完整帧，需要再次循环
            if((nextGroupDataLenth + newCompleteGroupData.getLength()) < (currentFrameSumLength - 2)) {
                System.arraycopy(nextGroupData.getBytes(), 0, newCompleteGroupBytes, newCompleteGroupData.getLength(), nextGroupData.getLength());
                newCompleteGroupData.setLength(newCompleteGroupData.getLength() + nextGroupDataLenth);
                nextGroupData.setStatus("HANDLE_SUCCESS");
                nextIndex = nextIndex + 1;
                continue;
            }

            // 说明当前索引对应的帧数据存在跨越，一部分是上一帧，一部分是下一帧
            if((nextGroupDataLenth + newCompleteGroupData.getLength()) > (currentFrameSumLength - 2)) {
                // 处理上一帧剩余数据，此处已经处理完,restNeedReadBytesCount的计算会导致数组越界异常，必须控制好，需要减去帧总长度两个字节
                int restNeedReadBytesCount = (currentFrameSumLength - 2)  - newCompleteGroupData.getLength();
                System.arraycopy(nextGroupData.getBytes(), 0, newCompleteGroupBytes, newCompleteGroupData.getLength(), restNeedReadBytesCount);
                newCompleteGroupData.setLength(newCompleteGroupData.getLength() + restNeedReadBytesCount);
                newCompleteGroupData.setBytes(newCompleteGroupBytes);
                currentEventModel.getCompleteList().add(newCompleteGroupData);

                // 处理当前帧缓存数据剩余字节,即变为新的缓存字节数组
                byte[] restBytes = new byte[nextGroupDataLenth - restNeedReadBytesCount];
                //System.arraycopy(restBytes, 0, nextGroupData.getBytes(), restNeedReadBytesCount, restBytes.length);
                System.arraycopy(nextGroupData.getBytes(), restNeedReadBytesCount, restBytes, 0, restBytes.length);
                nextGroupData.setLength(restBytes.length);
                nextGroupData.setBytes(restBytes);
                nextGroupData.setStatus("UN_HANDLE");
                break;
            }
        }

        currentGroupData.setStatus("HANDLE_SUCCESS");
        return nextIndex;
    }

    /**
     * 当前帧总长度数据小于当前通道缓存数据处理逻辑
     * @param currentChannelCacheDataModel 当前通道缓存对象
     * @param currentGroupData 当前通道待处理缓存数据
     * @param currentIndex 当前groupData对应在通道缓存对象中的索引
     * @param currentFrameSumLength 当前通道待处理缓存数据解析出的帧总长度
     * @param currentEventModel 当前通道事件数据模型
     */
    private int parseFrameSumLengthSmallerThanCurrentGroupDataLength(ChannelCacheDataModel currentChannelCacheDataModel,
        EventModel.GroupData currentGroupData, int currentIndex, int currentFrameSumLength, EventModel currentEventModel) {
        // 先处理上一帧数据
        EventModel.GroupData newCompleteGroupData = currentEventModel.new GroupData();
        byte[] newCompleteGroupBytes = new byte[currentFrameSumLength - 2];
        System.arraycopy(currentGroupData.getBytes(), 2, newCompleteGroupBytes, 0, newCompleteGroupBytes.length);
        newCompleteGroupData.setLength(newCompleteGroupBytes.length);
        newCompleteGroupData.setEndFrame(newCompleteGroupBytes[0]);
        byte[] indexBytes = new byte[4];
        indexBytes[0] = newCompleteGroupBytes[1];
        indexBytes[1] = newCompleteGroupBytes[2];
        indexBytes[2] = newCompleteGroupBytes[3];
        indexBytes[3] = newCompleteGroupBytes[4];
        newCompleteGroupData.setIndex(BasicUtil.byteArrayToInt(indexBytes));
        currentEventModel.getCompleteList().add(newCompleteGroupData);

        // 处理下一帧数据
        // 处理当前帧缓存数据剩余字节,即变为新的缓存字节数组
        byte[] restBytes = new byte[currentGroupData.getLength() - (2 + newCompleteGroupBytes.length)];
        System.arraycopy(currentGroupData.getBytes(), (2 + newCompleteGroupBytes.length), restBytes, 0, restBytes.length);
        currentGroupData.setLength(restBytes.length);
        currentGroupData.setBytes(restBytes);
        currentGroupData.setStatus("UN_HANDLE");
        return currentIndex;
    }

    /**
     * 添加完整待处理帧数据
     * @param currentChannelCacheDataModel 当前通道缓存对象
     * @param currentGroupData 当前通道新读入缓存数据
     * @param currentIndex 当前groupData对应在通道缓存对象中的索引
     * @param currentFrameSumLength 当前帧解析后指定的总长度
     * @param currentEventModel 当前通道事件数据模型
     */
    private void addCompleteFrameData(ChannelCacheDataModel currentChannelCacheDataModel, EventModel.GroupData currentGroupData, int currentIndex, int currentFrameSumLength, EventModel currentEventModel) {
        // 1、移除当前帧缓存数据中的帧总长度数据添加至新的byte数据
        byte[] newCompleteBytesData = new byte[currentFrameSumLength - 2];
        System.arraycopy(currentGroupData.getBytes(), 2, newCompleteBytesData, 0, (currentFrameSumLength - 2));
        EventModel.GroupData newCompleteGroupData = currentEventModel.new GroupData();
        newCompleteGroupData.setBytes(newCompleteBytesData);
        newCompleteGroupData.setLength(newCompleteBytesData.length);
        // 设置结束帧标记
        newCompleteGroupData.setEndFrame(newCompleteBytesData[0]);
        // 序号采用客户端发送过来的帧数据进行设置 1~4
        byte[] indexBytes = new byte[4];
        indexBytes[0] = newCompleteBytesData[1];indexBytes[1] = newCompleteBytesData[2];indexBytes[2] = newCompleteBytesData[3];indexBytes[3] = newCompleteBytesData[4];
        newCompleteGroupData.setIndex(BasicUtil.byteArrayToInt(indexBytes));
        currentEventModel.getCompleteList().add(newCompleteGroupData);
        currentGroupData.setStatus("HANDLE_SUCCESS");
    }

    /**
     * 判断当前通道内容是否能够进行业务处理，不能处理直接返回，直到能够进行业务处理
     * @param eventModel 当前通道事件数据模型
     * @param channelCacheDataModel 当前通道缓存数据
     * @param currentAddress 当前通道地址
     * @return 是否能够进行后续处理
     */
    private Boolean judgeIsCouldExecuteHandle(EventModel eventModel, ChannelCacheDataModel channelCacheDataModel, String currentAddress) {
        // 1、移除通道中成功处理的数据
        channelCacheDataModel.getList().removeIf(groupData -> groupData.getStatus().equals("HANDLE_SUCCESS"));

        // 2、如果有未成功处理的缓存数据，将剩余无法参与处理的数据重新计算帧索引值，之后再次通过SocketChannel读取时，继续往channelCacheDataModel.getList()后追加
        channelCacheDataModel.setIndex(1);
        if(!CollectionUtils.isEmpty(channelCacheDataModel.getList())) {
            for(EventModel.GroupData groupData : channelCacheDataModel.getList()) {
                groupData.setIndex(channelCacheDataModel.getIndex());
                channelCacheDataModel.setIndex(groupData.getIndex() + 1);
            }

            /*log.info("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] ReadEventHandler | --> 当前通道 [{}] 数据处理部分成功，存在未处理成功的帧数据，个数为 [{}], 未成功处理的数据将会在下次读取时处理", currentAddress, channelCacheDataModel.getList().size());*/
        }

        // 3、如果待处理的业务数据流为空，则直接返回
        if(CollectionUtils.isEmpty(eventModel.getCompleteList())) {
            //log.warn("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] ReadEventHandler | --> 当前通道 [{}] 待进行数据字节解析处理集合为空", currentAddress);
            return Boolean.FALSE;
        }

        return Boolean.TRUE;
    }

    /**
     * 粘包半包处理
     * @param originList 当前通道缓存原始数据（即从socketChannel读取到还未处理的数据，该数据会按照实际每帧中规定的帧总长度分组放入completeList）
     * @param completeList 解析校验后的需要保存的完整帧数据
     * @param eventModel  当前事件数据模型对象
     */
    private void stickingAndAalfWrapping(List<EventModel.GroupData> originList, List<EventModel.GroupData> completeList, EventModel eventModel) {
        //int sumLength = originList.stream().mapToInt(EventModel.GroupData::getLength).sum();
        //list.stream().collect(Collectors.summingInt(x -> ((FileMessageFrame) x.get(BasicConstant.FILE_FRAME)).getFrameIndex()));
        //byte[] bytes = new byte[sumLength];

        // 1、合并数组
        int completeFrameIndex = 0; // 当前完整帧序号
        int copyIndex = 0;
        int originListSize = originList.size();
        for(int i = 0; i < originList.size(); i++) { // 4000 4000 4000 4000 356

            /**
             * 1.1、判断是否发生粘包或是半包，根据byte[]数组中头两个字节进行判断
             *  1.1.1、无法解析帧长度数据: 即头两个字节解析出的当前帧总长度合法，且长度刚好等于byte[]数组长度
             *  1.1.2、能够解析出帧长度数据: 分两种情况:
             *      1.1.2.1、头两个字节无法解析出当前帧长度数据
             *      1.1.2.2、头两个字节正常解析出当前帧长度数据
             *          <>当前帧长度指定的数据长度 < 当前byte[]长度 ---> 发生粘包, 存在一部分数据是属于第二个byte[]</>
             *          <>当前帧长度指定的数据长度 > 当前byte[]长度 ---> 发生拆包，部分数据在第二个byte[]</>
             * */

            // 1.1、读取前两个字节, 判断总长度与bytes的比对，判断是否发生半包或是粘包帧
            EventModel.GroupData currentGroupData = originList.get(i);
            if(currentGroupData.getStatus().equals("HANDLE_SUCCESS")) {
                continue;
            }

            byte[] currentBytes = currentGroupData.getBytes();
            if(currentBytes.length == 0) {
                break;
            }

            // 1.2、得到当前帧总长度
            short frameSumLength = this.getFrameSumLengthBytes(currentBytes[0], currentBytes[1]);
            if(!(frameSumLength > 0)) { // 无法解析帧长度数据：头两个字节无法解析出当前帧长度数据，直接返回错误, 但是存在可能是其中的某个byte[]无法解析，但都返回错误,不触发后续的网络IO
                return;
            }

            // 1.3、未发生粘包半包处理 ---> 能够解析出帧长度数据,判断是否发生粘包或半包
            if(frameSumLength == currentBytes.length) { // 当前帧长度指定的完整数据刚好等于当前byte[]除掉帧长度数据后剩余的字节数，即最理想情况
                EventModel.GroupData groupData = this.readAssignBytes(currentGroupData, frameSumLength, eventModel);
                // 判断是否包含有结束帧
                if(groupData.getEndFrame() == (byte) 1) {
                    return;
                }
            }

            // 1.4、半包处理1 ---> 当前帧长度指定的完整数据小于当前byte[]除掉帧长度数据后剩余的字节数，发生粘包
            if(frameSumLength < currentBytes.length) {
                // 粘包处理第一步: 先正常处理当前帧中一组数据
                EventModel.GroupData groupData = this.readAssignBytes(currentGroupData, frameSumLength, eventModel);

                // 粘包处理第二步：再处理当前帧中粘连部分数据,判断粘连部分能否进行处理，临界情况为刚好粘连了第二个帧的第一个字节数据，其他数据都在第二个帧
                if(!((i + 1) >= originListSize)) {
                    EventModel.GroupData nextGroupData = originList.get(i + 1);
                    this.stickyPackage(nextGroupData, currentGroupData, frameSumLength, eventModel);
                }
            }

            // 1.5、半包处理2 ---> 当前帧长度指定的完整数据大于当前byte[]除掉帧长度数据后剩余的字节数，发生拆包
            if(frameSumLength > currentBytes.length) {
                // 拆包处理，当前currentBytes缺少数据，需要从第二个包进行读取
                if(!((i + 1) >= originListSize)) {
                    EventModel.GroupData nextGroupData = originList.get(i + 1);
                    this.unpacking(nextGroupData, currentGroupData, frameSumLength, eventModel);
                }
            }
        }
    }

    /**
     * 执行粘包处理  --> 处理逻辑：执行当前帧剩余字节处理，当前帧剩余的字节数至少要能获取到能够完整读取出第二个完整帧的长度数据，重新构建下一个数据包模型
     * @param nextGroupData 下一帧数据模型
     * @param currentGroupData 当前帧数据模型
     * @param frameSumLength  当前帧数据长度
     * @param eventModel  当前帧对应的事件模型对象
     */
    private void stickyPackage(EventModel.GroupData nextGroupData, EventModel.GroupData currentGroupData, int frameSumLength, EventModel eventModel) {
        // 1、获取当前包剩余字节个数，粘包时: frameSumLength 必定小于 currentBytes长度 --> 获取当前包剩余字节个数
        int currentFrameRestByteLength = currentGroupData.getBytes().length - frameSumLength;
        if(currentFrameRestByteLength == 1) {
            // 说明当前帧粘连的数据不够2个字节，即第二个完整帧数据的总长度对应的字节数据，有一个字节在第一个bytes中，有一个字节在第二个bytes中，需要下次循环才能处理，此时需要将多出的字节追加进第二个字节数组中
            byte[] bytes = nextGroupData.getBytes();
            // 复制当前帧剩余一个字节的数据
            byte[] newCopyBytes = new byte[bytes.length + 1];
            newCopyBytes[0] = currentGroupData.getBytes()[frameSumLength + 1];
            System.arraycopy(bytes, 0, newCopyBytes, 1, bytes.length);
            nextGroupData.setLength(newCopyBytes.length);
            nextGroupData.setBytes(newCopyBytes);
            return;
        }

        // 1.1、对当前包剩余的字节数组进行再次处理
        short nextFrameSumLength = this.getFrameSumLengthBytes(currentGroupData.getBytes()[frameSumLength], currentGroupData.getBytes()[frameSumLength + 1]); //3081

        // 1.2、获取需要从下一个包读取的真实字节数个数 --> 下一个待处理包总字节数 - 帧总长度字节数 - 当前包剩余字节数 - 剩余字节数中包含的当前帧总大小字节数
        int needReadByteLengthFromNextFrame = (nextFrameSumLength - 2) - (currentFrameRestByteLength - 2);

        // 2、此处追加判断，根据已经计算出需要从下一帧读取的字节数(needReadByteLengthFromNextFrame)来判断能否从下一帧读取完全，不够则无法处理，需要缓存无法处理的帧数据
        if(needReadByteLengthFromNextFrame > nextGroupData.getLength()) { // 无法读取，字节数不够
            // 需要跨帧多次读取或无法处理(即读取下一完整帧字节数依旧不够，还要再读取下一帧，由于下一帧还未出于应用缓存空间，则禁止处理)
            // 帧对以上两种情况，直接将当前帧剩余字节数复制到下一帧中，由下次循环进行处理
            byte[] appendFrameBytes = new byte[currentFrameRestByteLength + nextGroupData.getLength()];
            // 从当前帧拷贝剩余总字节数
            System.arraycopy(currentGroupData.getBytes(), 0, appendFrameBytes, 0, currentFrameRestByteLength);
            // 从下一个帧拷贝总字节数
            System.arraycopy(nextGroupData.getBytes(), 0, appendFrameBytes, currentFrameRestByteLength, nextGroupData.getLength());
            nextGroupData.setLength(appendFrameBytes.length);
            nextGroupData.setBytes(null); // help GC
            nextGroupData.setBytes(appendFrameBytes);

            currentGroupData.setStatus("HANDLE_SUCCESS");
        } else { // 足够则正常读取
            // 追加由于粘包造成的当前帧数据的再次处理  当前帧剩余字节数 + 需要从下一个帧读取的字节个数 - 剩余字节数中包含的当前帧总大小字节数 = 真实待处理帧数据
            byte[] appendFrameBytes = new byte[nextFrameSumLength - 2];
            // 从当前帧拷贝剩余真实字节数
            System.arraycopy(currentGroupData.getBytes(), (frameSumLength + 2), appendFrameBytes, 0, (currentFrameRestByteLength - 2));
            // 从下一个帧拷贝剩余真实字节数
            System.arraycopy(nextGroupData.getBytes(), 0, appendFrameBytes, (currentFrameRestByteLength - 2), needReadByteLengthFromNextFrame);

            // 设置可以进行处理的完整帧()
            EventModel.GroupData completeGroupData = eventModel.new GroupData();
            completeGroupData.setStatus("FAIL");
            completeGroupData.setLength(appendFrameBytes.length);
            completeGroupData.setBytes(appendFrameBytes);
            // 设置结束帧标记
            completeGroupData.setEndFrame(appendFrameBytes[0]);
            // 序号采用客户端发送过来的帧数据进行设置 1~4
            byte[] indexBytes = new byte[4];
            indexBytes[0] = appendFrameBytes[1];indexBytes[1] = appendFrameBytes[2];indexBytes[2] = appendFrameBytes[3];indexBytes[3] = appendFrameBytes[4];
            completeGroupData.setIndex(BasicUtil.byteArrayToInt(indexBytes));
            eventModel.getCompleteList().add(completeGroupData);

            // 当前帧处理完后判断，在当前帧处理过程中如果从下一帧拷贝过来的数据刚好等于下一个帧数据，说明最后一帧就是当前数据流的末尾，直接将下一帧设置为读取完成,如果不是，那说明依旧发生了半包，缓存等待下次处理
            if(needReadByteLengthFromNextFrame == nextGroupData.getLength()) {
                nextGroupData.setStatus("HANDLE_SUCCESS");
            } else {
                // 重新定义下一个帧数据: 本身的数据长度 + 追加的长度, 那么下次循环的时候实际处理的字节数为下一帧原本字节数减去已经复制到上一帧中剩余的字节数
                int nextFrameNewSumLength = nextGroupData.getLength() - needReadByteLengthFromNextFrame;
                // 开辟新的数组，复制粘包数据
                byte[] newFrameBytes = new byte[nextFrameNewSumLength];
                // 拷贝下一个帧剩余字节数据
                System.arraycopy(nextGroupData.getBytes(), needReadByteLengthFromNextFrame, newFrameBytes, 0, nextFrameNewSumLength);
                nextGroupData.setLength(nextFrameNewSumLength);
                nextGroupData.setBytes(newFrameBytes);
            }

            // 当前帧设置已处理完成
            currentGroupData.setStatus("HANDLE_SUCCESS");
        }
    }

    /**
     * 执行拆包处理
     * @param nextGroupData 下一帧数据模型
     * @param currentGroupData 当前帧数据模型
     * @param frameSumLength  当前帧数据长度
     * @param eventModel  当前帧对应的事件模型对象
     */
    private void unpacking(EventModel.GroupData nextGroupData, EventModel.GroupData currentGroupData, int frameSumLength, EventModel eventModel) {
        // 1、拆包时，frameSumLength 必定大于 currentBytes长度，需要从下一个包拆出指定个数数据 --> 获取需要从下一个包拆出的真实字节个数
        int needReadByteLengthFromNextFrame = (frameSumLength - 2) - (currentGroupData.getLength() - 2);

        // 2、此处追加判断，根据已经计算出需要从下一帧读取的字节数(needReadByteLengthFromNextFrame)来判断能否从下一帧读取完全，不够则无法处理，需要缓存无法处理的帧数据
        if(needReadByteLengthFromNextFrame > nextGroupData.getLength()) { // 无法读取，字节数不够, 直接将当前帧完全复制进下一帧,当前帧设置为已处理完
            // 需要跨帧多次读取或无法处理(即读取下一完整帧字节数依旧不够，还要再读取下一帧，由于下一帧还未出于应用缓存空间，则禁止处理)
            // 帧对以上两种情况，直接将当前帧剩余字节数复制到下一帧中，由下次循环进行处理
            byte[] appendFrameBytes = new byte[currentGroupData.getLength() + nextGroupData.getLength()];
            // 从当前帧拷贝剩余总字节数
            System.arraycopy(currentGroupData.getBytes(), 0, appendFrameBytes, 0, currentGroupData.getLength());
            // 从下一个帧拷贝总字节数
            System.arraycopy(nextGroupData.getBytes(), 0, appendFrameBytes, currentGroupData.getLength(), nextGroupData.getLength());
            nextGroupData.setLength(appendFrameBytes.length);
            nextGroupData.setBytes(null); // help GC
            nextGroupData.setBytes(appendFrameBytes);
            currentGroupData.setStatus("HANDLE_SUCCESS");
        } else { // 足够则正常读取
            // 1.1、先处理当前包数据
            byte[] appendFrameBytes = new byte[frameSumLength - 2];
            // 从当前帧拷贝剩余字节数
            System.arraycopy(currentGroupData.getBytes(), 2, appendFrameBytes, 0, (currentGroupData.getLength() - 2));
            // 从下一个帧拷贝剩余字节数
            System.arraycopy(nextGroupData.getBytes(), 0, appendFrameBytes, (currentGroupData.getLength() - 2), needReadByteLengthFromNextFrame);

            EventModel.GroupData completeGroupData = eventModel.new GroupData();
            completeGroupData.setStatus("FAIL");
            completeGroupData.setLength(appendFrameBytes.length);
            completeGroupData.setBytes(appendFrameBytes);
            // 设置结束帧标记
            completeGroupData.setEndFrame(appendFrameBytes[0]);
            // 序号采用客户端发送过来的帧数据进行设置 1~4
            byte[] indexBytes = new byte[4];
            indexBytes[0] = appendFrameBytes[1];indexBytes[1] = appendFrameBytes[2];indexBytes[2] = appendFrameBytes[3];indexBytes[3] = appendFrameBytes[4];
            completeGroupData.setIndex(BasicUtil.byteArrayToInt(indexBytes));
            eventModel.getCompleteList().add(completeGroupData);

            // 当前帧处理完后判断，在当前帧处理过程中如果从下一帧拷贝过来的数据刚好等于下一个帧数据，说明最后一帧就是当前数据流的末尾，直接将下一帧设置为读取完成,如果不是，那说明依旧发生了半包，缓存等待下次处理
            if(needReadByteLengthFromNextFrame == nextGroupData.getLength()) {
                nextGroupData.setStatus("HANDLE_SUCCESS");
            } else {
                // 重新定义下一个帧数据: 本身的数据长度 + 追加的长度
                int nextFrameNewSumLength = nextGroupData.getLength() - needReadByteLengthFromNextFrame;
                // 开辟新的数组，复制粘包数据
                byte[] newFrameBytes = new byte[nextFrameNewSumLength];
                // 拷贝下一个帧剩余字节数据
                System.arraycopy(nextGroupData.getBytes(), needReadByteLengthFromNextFrame, newFrameBytes, 0, nextFrameNewSumLength);
                nextGroupData.setLength(nextFrameNewSumLength);
                nextGroupData.setBytes(null); // help GC
                nextGroupData.setBytes(newFrameBytes);
            }

            // 当前帧设置已处理完成
            currentGroupData.setStatus("HANDLE_SUCCESS");
        }
    }

    /**
     * 读取当前帧总长度
     * @param b1
     * @param b2
     * @return 返回当前帧总长度
     */
    private short getFrameSumLengthBytes(byte b1, byte b2) {
        StringBuilder stringBuilder = new StringBuilder("");

        // 1、解析帧类型
        byte[] shortBytes = new byte[2];
        shortBytes[0] = b1;
        shortBytes[1] = b2;
        return ByteOrderConvert.bytesToShort(shortBytes);
    }

    /**
     * 读取指定长度数据
     * @param currentGroupData 当前帧数据模型
     * @param frameSumLength 当前帧长度
     * @param eventModel 当前帧对应的事件模型对象
     * @return
     */
    private EventModel.GroupData readAssignBytes(EventModel.GroupData currentGroupData, short frameSumLength, EventModel eventModel) {
        EventModel.GroupData completeGroupData = eventModel.new GroupData();
        completeGroupData.setLength(frameSumLength - 2);
        byte[] currentFrameBytes = new byte[frameSumLength - 2];
        System.arraycopy(currentGroupData.getBytes(), 2, currentFrameBytes, 0, (frameSumLength - 2));
        completeGroupData.setBytes(currentFrameBytes);
        // 设置结束帧标记 0
        completeGroupData.setEndFrame(currentFrameBytes[0]);
        // 序号采用客户端发送过来的帧数据进行设置 1~4
        byte[] indexBytes = new byte[4];
        indexBytes[0] = currentFrameBytes[1];indexBytes[1] = currentFrameBytes[2];indexBytes[2] = currentFrameBytes[3];indexBytes[3] = currentFrameBytes[4];
        completeGroupData.setIndex(BasicUtil.byteArrayToInt(indexBytes));
        eventModel.getCompleteList().add(completeGroupData);
        currentGroupData.setStatus("HANDLE_SUCCESS");
        return completeGroupData;
    }
}
