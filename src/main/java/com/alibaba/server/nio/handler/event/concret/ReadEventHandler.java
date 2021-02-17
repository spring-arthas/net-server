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
    private static Integer chatIndex = 0;

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

            // 2.1、获取当前通道对应的subReactor线程
            SubReactor reactor = GlobalMainReactor.getSubReactorForSocketChannel((SocketChannel) eventModel.getSelectionKey().channel());
            if(!Optional.ofNullable(reactor).isPresent()) {
                // 为空，表示没有找到当前通道对应的Subreacto线程，有可能为之前用户下线导致清除了用户对应的SubReactor线程，但是socketChannel并未断开，故此时重新建立新的Subreactor即可
                this.registerSubReactor(eventModel, BasicConstant.NIO_SERVER_MAIN_CORE_CHAT_SELECTOR);
                return eventModel;
            }

            // 2.2、将当前通道触发的读事件数据 --> 加入当前SocketChannel连接对应的的Subreactor线程数据处理队列, 可用空间必须大于0
            if(reactor.getQueue().remainingCapacity() > 0) {
                Map<String, Object> queueMap = new HashMap<>();
                queueMap.put("SOCKET_CHANNEL_CONTEXT",eventModel.getSelectionKey().attachment());
                queueMap.put("COMPLETE_LIST", eventModel.getCompleteList());
                reactor.getQueue().offer(queueMap);
                return eventModel;
            }

            log.warn("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] ReadEventHandler | --> 聊天服务通道 [{}] 对应的SubReactor线程数据处理队列已满, 队列可用空间 = [{}], address = {}, thread = {}", ((SocketChannelContext) eventModel.getSelectionKey().attachment()).getRemoteAddress(), reactor.getQueue().remainingCapacity(), Thread.currentThread().getName());
            return eventModel;
        }

        // 3、文件业务
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
        int readBytes = 0;
        try {
            while ((readBytes = socketChannel.read(byteBuffer)) > 0 ) {
                // 每读取一次做为一个GroupData进行封装，但是每read一次的大小不一定按照byteBuffer指定大小进行读取
                byteBuffer.flip();
                if (byteBuffer.hasRemaining()) {
                    byte[] bytes = new byte[byteBuffer.remaining()];
                    // 按照发送的次序依次从byteBuffer读取进bytes数组
                    byteBuffer.get(bytes);

                    // 将当前数据进行封装,每读取一次执行一次处理
                    EventModel.GroupData groupData = eventModel.new GroupData();
                    groupData.setLength(bytes.length);
                    groupData.setIndex(channelCacheDataModel.getIndex());
                    groupData.setBytes(bytes);
                    groupData.setStatus("UN_HANDLE");
                    channelCacheDataModel.setIndex(groupData.getIndex() + 1);

                    // 验证及处理当前帧
                    int result = this.verificateHandleCurrentGroupData(channelCacheDataModel, groupData, eventModel);
                    if(result == -2) {
                        throw new RuntimeException("通道 [" + socketChannelContext.getRemoteAddress() + "] 解析当前帧 [序号 = " + groupData.getIndex() + "] 的帧总长度数据错误");
                    }

                    if(result == -3) {
                        throw new RuntimeException("通道 [" + socketChannelContext.getRemoteAddress() + "] 解析当前帧 [序号 = " + groupData.getIndex() + "] 发生未知错误");
                    }

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
        } catch (Exception e) {
            // 处理read产生的异常
            if(e instanceof SocketException) {
                // Connection reset || Connection reset by peer:Socket write error || Broken pipe
            }

            if(e instanceof ClosedChannelException) { // socketChanel 已经关闭依旧发生read
                log.warn("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] ReadEventHandler | --> can not read data at a closed socketChannel}");
            }

            e.printStackTrace();
            eventModel.getSelectionKey().cancel();
            NioServerContext.closedAndRelease(socketChannel);
            return Boolean.FALSE;
        } finally {
            byteBuffer.clear();
        }

        // 2、判断当前数据能否处理多少，能达到多少处理标准就处理多少数据
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
     *      基本位数据: 4 + 1 + 4 + 3 + [动态大小] = 12 + [动态大小]
     *          帧组成: 4B : 当前帧总长度
     *                 1B : 当前帧是否是结束帧
     *                 4B : 当前帧发送序号(由客户端确定)
     *                 3B : 帧基本数据 [1B : 帧类型, 1B : 文件类型, 1B : 文件操作类型]
     *            动态大小 : [4B + 不确定大小]，其中4B为动态数据长度大小(int 4字节)
     */
    private int verificateHandleCurrentGroupData(ChannelCacheDataModel currentChannelCacheDataModel, EventModel.GroupData currentGroupData, EventModel currentEventModel) {
        // 1、判断当前数据长度是否能够处理，即校验基本位字节段 int orginStreamDataIndex = 4 + 1 + 4 + 3 + sendDataLengthBytes.Length + sendDataBytes.Length;
        if(!CollectionUtils.isEmpty(currentChannelCacheDataModel.getList())) {
            List<EventModel.GroupData> cacheList = currentChannelCacheDataModel.getList();
            // 先将新读入的数据追加至当前通道缓存对象数据集合末尾
            cacheList.add(currentGroupData);
            // 当前通道缓存数据排序(升序排序)，保证处理顺序的按照读入的顺序
            cacheList.stream().sorted(Comparator.comparing(EventModel.GroupData::getIndex));

            // 1.1、处理缓存集合中的数据
            int currentIndex = 0;
            while (currentIndex < cacheList.size()) {
                currentIndex = this.executeParseCurrentGroupData(currentChannelCacheDataModel, cacheList.get(currentIndex), currentIndex, currentEventModel);

                // 返回 -1 或 cacheList缓存越界，直接返回，执行下一次socketChannel.read()读取
                if(currentIndex == -1 || (currentIndex >= cacheList.size())) {
                    return -1;
                }

                // 判断当前索引[currentIndex]指示的GroupData是否处于处理完成状态，如果是则索引自增1，开始处理下一份GourpData，如果不是则当前索引无需自增1，直接当前索引继续处理
                // 备注：不是UN_HANDLE状态，表示当前索引对应的GroupData发生了粘包，一部分数据为上一帧的数据，一部分为下一帧的数据，所以需要继续处理
                EventModel.GroupData nextIndexGroupData = cacheList.get(currentIndex);
                if(StringUtils.equals(nextIndexGroupData.getStatus(), "UN_HANDLE")) {
                    // 处理前判断帧的长度是否小于16B，小于16表示帧的字节长度不够处理需要重新读取那么此时返回-1，如果大于16B则直接处理
                    Boolean result = this.frameSumLengthSmallerThan16(currentChannelCacheDataModel, nextIndexGroupData, currentIndex);
                    if(!result) {
                        // 将小于16的当前帧复制到下一帧失败，说明没有下一帧，需要重新开启socketChannel.read()读取，此时直接返回
                        return -1;
                    }

                    // 此处已经将小于16个字节的基础数据拷贝至下一个GroupData，并移除了小于16的帧对应GroupData, 那么索引无需变化，直接当前索引对应的GroupData进行处理
                    continue;
                }

                // 如果没有剩余，说明刚好加上 [currentIndex] 指定的GroupData所有数据刚好等于currentFrameSumLength，那么直接自增1，执行下一次处理
                currentIndex++;
            }

            // 都不符合，则返回-3，位置错误，关闭当前SocketChannel，如果是文件服务，还需关闭文件对应的通道
            return -3;
        } else {
            // 2、当前帧GroupData字节长度直接小于16，则追加进缓存队列，返回-1，执行下次socketChannel.read()读取完成后重新处理
            if(currentGroupData.getLength() < 16) {
                currentChannelCacheDataModel.getList().add(currentGroupData);
                return -1;
            }

            // 3、大于16则直接处理
            currentChannelCacheDataModel.getList().add(currentGroupData);
            return this.executeParseCurrentGroupData(currentChannelCacheDataModel, currentGroupData, 0, currentEventModel);
        }
    }

    /**
     * 处理当前帧数据长度小于16
     * @param currentChannelCacheDataModel 当前通道缓存对象
     * @param currentGroupData 当前通道待处理缓存数据
     * @param currentIndex 当前groupData对应在通道缓存对象中的索引
     * @return
     */
    private Boolean frameSumLengthSmallerThan16(ChannelCacheDataModel currentChannelCacheDataModel, EventModel.GroupData nextIndexGroupData, int currentIndex) {
        // 获取下一个GroupData
        if((currentIndex + 1) >= currentChannelCacheDataModel.getList().size()) {
            return Boolean.FALSE;
        }

        EventModel.GroupData nextIndex2GroupData = currentChannelCacheDataModel.getList().get(currentIndex + 1);
        byte[] newBytes = new byte[nextIndexGroupData.getLength() + nextIndex2GroupData.getLength()];
        System.arraycopy(nextIndexGroupData.getBytes(), 0, newBytes, 0, nextIndexGroupData.getLength());
        System.arraycopy(nextIndex2GroupData.getBytes(), 0, newBytes, nextIndexGroupData.getLength(), nextIndex2GroupData.getLength());
        nextIndex2GroupData.setLength(newBytes.length);
        nextIndex2GroupData.setBytes(newBytes);
        nextIndex2GroupData.setStatus("UN_HANDLE");

        // 移除被复制到下一个帧的当前帧
        currentChannelCacheDataModel.getList().remove(nextIndexGroupData);
        return Boolean.TRUE;
    }

    /**
     * 处理当前数据，此时当前帧数据最应该大于16，但是存在首次socketChannel.read()读取直接就小于16，那么需要执行一次小于16的特殊处理
     * @param currentChannelCacheDataModel 当前通道缓存对象
     * @param currentGroupData 当前通道待处理缓存数据
     * @param currentIndex 当前groupData对应在通道缓存对象中的索引
     * @param currentEventModel 当前通道事件数据模型
     */
    private int executeParseCurrentGroupData(ChannelCacheDataModel currentChannelCacheDataModel, EventModel.GroupData currentGroupData, int currentIndex, EventModel currentEventModel) {
        int currentGroupDataLength = currentGroupData.getLength();
        // 1、判断当前帧长度是否小于16，如果小于16需要将当前帧字节数据复制到下一个字节
        if(currentGroupData.getLength() < 16) {
            Boolean result = this.frameSumLengthSmallerThan16(currentChannelCacheDataModel, currentGroupData, currentIndex);
            if(!result) {
                // 将小于16的当前帧复制到下一帧失败，说明没有下一帧，需要重新开启socketChannel.read()读取，此时直接返回
                return -1;
            }
            // 此处已经将小于16个字节的基础数据拷贝至下一个GroupData，并移除了小于16的帧对应GroupData, 那么索引无需变化，直接当前索引对应的GroupData进行处理
            return currentIndex;
        }

        // 2、解析当前帧总长度4B
        int currentFrameSumLength = this.getFrameSumLengthBytes(currentGroupData);
        if(currentFrameSumLength <= 0) {
            log.info("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] ReadEventHandler | --> 通道 [{}] 解析当前帧 [序号 = {}] 的帧总长度数据错误, 解析出来的帧总长度 = [{}]",
                currentEventModel.getRemoteAddress(), currentGroupData.getIndex(), currentFrameSumLength);
            // 帧总长度解析错误，返回-2，关闭当前SocketChannel通道，如果存在文件通道还需关闭文件通道
            return -2;
        }

        // 3、如果当前帧指定的总长度(currentFrameSumLength) = 当前帧缓存数据长度  -->  刚好能够处理完整帧
        if(currentFrameSumLength == currentGroupDataLength) {
            addCompleteFrameData(currentChannelCacheDataModel, currentGroupData, currentIndex, currentFrameSumLength, currentEventModel);
            return currentIndex;
        }

        // 4、如果当前帧指定的总长度(currentFrameSumLength) > 当前帧缓存数据长度  -->  完整帧的数据需要跨越多个groupData
        if(currentFrameSumLength > currentGroupDataLength) {
            return parseFrameSumLengthBiggerThanCurrentGroupDataLength(currentChannelCacheDataModel, currentGroupData, currentIndex, currentFrameSumLength, currentEventModel);
        }

        // 5、如果当前帧指定的总长度(currentFrameSumLength) > 当前帧缓存数据长度  -->  完整帧在当前帧中处理完还存在剩余
        if(currentFrameSumLength < currentGroupDataLength) {
            return parseFrameSumLengthSmallerThanCurrentGroupDataLength(currentChannelCacheDataModel, currentGroupData, currentIndex, currentFrameSumLength, currentEventModel);
        }

        // 都不符合，则返回-3，位置错误，关闭当前SocketChannel，如果是文件服务，还需关闭文件对应的通道
        return -3;
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
        // 1、先处理当前通道数据，构建新的
        EventModel.GroupData newCompleteGroupData = currentEventModel.new GroupData();
        byte[] newCompleteGroupBytes = new byte[currentFrameSumLength - 4];
        System.arraycopy(currentGroupData.getBytes(), 4, newCompleteGroupBytes, 0, (currentGroupData.getLength() - 4));
        newCompleteGroupData.setLength(currentGroupData.getLength() - 4);
        newCompleteGroupData.setEndFrame(newCompleteGroupBytes[0]);
        byte[] indexBytes = new byte[4];
        indexBytes[0] = newCompleteGroupBytes[1];
        indexBytes[1] = newCompleteGroupBytes[2];
        indexBytes[2] = newCompleteGroupBytes[3];
        indexBytes[3] = newCompleteGroupBytes[4];
        newCompleteGroupData.setIndex(BasicUtil.byteArrayToInt(indexBytes));

        // 2、提前从当前通道缓存中根据当前索引获取下一份数据
        int nextIndex = currentIndex + 1;
        EventModel.GroupData nextGroupData = null;
        while (true) {
            // 3、下一个帧缓存数据索引,并判断下一帧缓存数据是否存在，不存在则不处理
            if(nextIndex >= currentChannelCacheDataModel.getList().size()) {
                // 此处如果循环过程出现索引越界，那说明当前帧都没有处理完，虽说跨过几帧，但是都是白处理的，所以此处还是需要将当前处理的GroupData设置为未处理后返回
                currentChannelCacheDataModel.getList().stream().forEach(groupData -> groupData.setStatus("UN_HANDLE"));
                return nextIndex;
            }

            nextGroupData = currentChannelCacheDataModel.getList().get(nextIndex);
            int nextGroupDataLenth = nextGroupData.getLength();
            if((nextGroupDataLenth + newCompleteGroupData.getLength()) == (currentFrameSumLength - 4)) {
                System.arraycopy(nextGroupData.getBytes(), 0, newCompleteGroupBytes, newCompleteGroupData.getLength(), nextGroupDataLenth);
                newCompleteGroupData.setLength(newCompleteGroupData.getLength() + nextGroupDataLenth);
                newCompleteGroupData.setBytes(newCompleteGroupBytes);
                nextGroupData.setStatus("HANDLE_SUCCESS");
                currentEventModel.getCompleteList().add(newCompleteGroupData);

                // 处理完当前帧，移除已经为HANDLE_SUCCESS状态的数据包
                currentGroupData.setStatus("HANDLE_SUCCESS");
                List<EventModel.GroupData> removeList = Lists.newArrayList();
                for(int i = 0; (i <= nextIndex && i < currentChannelCacheDataModel.getList().size()); i++) {
                    if("HANDLE_SUCCESS".equals(currentChannelCacheDataModel.getList().get(i).getStatus())) {
                        removeList.add(currentChannelCacheDataModel.getList().get(i));
                    }
                }
                currentChannelCacheDataModel.getList().removeAll(removeList);
                break;
            }

            // 4、说明当前索引对应的帧数据存在跨越，即下一帧依旧未能获取到完整帧，需要再次循环
            if((nextGroupDataLenth + newCompleteGroupData.getLength()) < (currentFrameSumLength - 4)) {
                System.arraycopy(nextGroupData.getBytes(), 0, newCompleteGroupBytes, newCompleteGroupData.getLength(), nextGroupData.getLength());
                newCompleteGroupData.setLength(newCompleteGroupData.getLength() + nextGroupDataLenth);
                nextGroupData.setStatus("HANDLE_SUCCESS");
                nextIndex = nextIndex + 1;
                continue;
            }

            // 5、说明当前索引对应的帧数据存在跨越，一部分是上一帧，一部分是下一帧
            if((nextGroupDataLenth + newCompleteGroupData.getLength()) > (currentFrameSumLength - 4)) {
                // 5.1、处理上一帧剩余数据，此处已经处理完,restNeedReadBytesCount的计算会导致数组越界异常，必须控制好，需要减去帧总长度两个字节
                int restNeedReadBytesCount = (currentFrameSumLength - 4)  - newCompleteGroupData.getLength();
                System.arraycopy(nextGroupData.getBytes(), 0, newCompleteGroupBytes, newCompleteGroupData.getLength(), restNeedReadBytesCount);
                newCompleteGroupData.setLength(newCompleteGroupData.getLength() + restNeedReadBytesCount);
                newCompleteGroupData.setBytes(newCompleteGroupBytes);
                currentEventModel.getCompleteList().add(newCompleteGroupData);

                // 5.2、处理当前帧缓存数据剩余字节,即变为新的缓存字节数组
                byte[] restBytes = new byte[nextGroupDataLenth - restNeedReadBytesCount];
                System.arraycopy(nextGroupData.getBytes(), restNeedReadBytesCount, restBytes, 0, restBytes.length);
                nextGroupData.setLength(restBytes.length);
                nextGroupData.setBytes(restBytes);
                nextGroupData.setStatus("UN_HANDLE");

                // 5.3、处理完当前帧，移除已经为HANDLE_SUCCESS状态的数据包
                currentGroupData.setStatus("HANDLE_SUCCESS");
                List<EventModel.GroupData> removeList = Lists.newArrayList();
                for(int i = 0; (i < nextIndex && i < currentChannelCacheDataModel.getList().size()); i++) {
                    if("HANDLE_SUCCESS".equals(currentChannelCacheDataModel.getList().get(i).getStatus())) {
                        removeList.add(currentChannelCacheDataModel.getList().get(i));
                    }
                }
                currentChannelCacheDataModel.getList().removeAll(removeList);

                // 5.4、下一帧处理完时，需要判断下一帧长度是否小于16，小于16需要继续复制到下一帧
                Boolean result = this.frameSumLengthSmallerThan16(currentChannelCacheDataModel, nextGroupData, nextIndex);
                if(!result) {
                    // 将小于16的当前帧复制到下一帧失败，说明没有下一帧，需要重新开启socketChannel.read()读取，此时直接返回
                    return -1;
                }
                break;
            }
        }
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
        // 1、处理当前GroupData属于上一帧的数据
        EventModel.GroupData newCompleteGroupData = currentEventModel.new GroupData();
        byte[] newCompleteGroupBytes = new byte[currentFrameSumLength - 4];
        System.arraycopy(currentGroupData.getBytes(), 4, newCompleteGroupBytes, 0, newCompleteGroupBytes.length);
        newCompleteGroupData.setLength(newCompleteGroupBytes.length);
        newCompleteGroupData.setEndFrame(newCompleteGroupBytes[0]);
        byte[] indexBytes = new byte[4];
        indexBytes[0] = newCompleteGroupBytes[1];
        indexBytes[1] = newCompleteGroupBytes[2];
        indexBytes[2] = newCompleteGroupBytes[3];
        indexBytes[3] = newCompleteGroupBytes[4];
        newCompleteGroupData.setIndex(BasicUtil.byteArrayToInt(indexBytes));
        currentEventModel.getCompleteList().add(newCompleteGroupData);

        // 2、处理当前GroupData属于下一帧的数据，即移除上一帧的字节数据，只保留属于下一帧的字节数据，同时当前GroupData依旧是未处理状态[UN_HANDLE]
        byte[] restBytes = new byte[currentGroupData.getLength() - (4 + newCompleteGroupBytes.length)];
        System.arraycopy(currentGroupData.getBytes(), (4 + newCompleteGroupBytes.length), restBytes, 0, restBytes.length);
        currentGroupData.setLength(restBytes.length);
        currentGroupData.setBytes(restBytes);
        currentGroupData.setStatus("UN_HANDLE");

        // 3、处理完当前帧，移除索引小于 [currentIndex] 之前已经为HANDLE_SUCCESS状态的数据包
        List<EventModel.GroupData> removeList = Lists.newArrayList();
        for(int i = 0; (i < currentIndex && i < currentChannelCacheDataModel.getList().size()); i++) {
            if("HANDLE_SUCCESS".equals(currentChannelCacheDataModel.getList().get(i).getStatus())) {
                removeList.add(currentChannelCacheDataModel.getList().get(i));
            }
        }
        currentChannelCacheDataModel.getList().removeAll(removeList);

        // 4、下一帧处理完时，需要判断下一帧长度是否小于16，小于16需要继续复制到下一帧
        Boolean result = this.frameSumLengthSmallerThan16(currentChannelCacheDataModel, currentGroupData, currentIndex);
        if(!result) {
            // 将小于16的当前帧复制到下一帧失败，说明没有下一帧，需要重新开启socketChannel.read()读取，此时直接返回
            return -1;
        }
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
        byte[] newCompleteBytesData = new byte[currentFrameSumLength - 4];
        System.arraycopy(currentGroupData.getBytes(), 4, newCompleteBytesData, 0, (currentFrameSumLength - 4));
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

        List<EventModel.GroupData> removeList = Lists.newArrayList();
        for(int i = 0; (i <= currentIndex && i < currentChannelCacheDataModel.getList().size()); i++) {
            if(i <= currentIndex && "HANDLE_SUCCESS".equals(currentChannelCacheDataModel.getList().get(i).getStatus())) {
                removeList.add(currentChannelCacheDataModel.getList().get(i));
            }
        }
        currentChannelCacheDataModel.getList().removeAll(removeList);
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
     * 读取当前帧总长度
     * @param b1
     * @param b2
     * @return 返回当前帧总长度
     */
    private int getFrameSumLengthBytes(EventModel.GroupData currentGroupData) {
        StringBuilder stringBuilder = new StringBuilder("");

        // 1、解析帧总长度
        byte[] indexBytes = new byte[4];
        indexBytes[0] = currentGroupData.getBytes()[0];
        indexBytes[1] = currentGroupData.getBytes()[1];
        indexBytes[2] = currentGroupData.getBytes()[2];
        indexBytes[3] = currentGroupData.getBytes()[3];
        return BasicUtil.byteArrayToInt(indexBytes);
    }
}
