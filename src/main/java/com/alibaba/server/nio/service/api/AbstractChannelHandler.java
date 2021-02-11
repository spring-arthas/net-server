package com.alibaba.server.nio.service.api;

import com.alibaba.server.common.BasicConstant;
import com.alibaba.server.nio.core.server.BasicServer;
import com.alibaba.server.nio.core.server.NioServerContext;
import com.alibaba.server.nio.model.SocketChannelContext;
import com.alibaba.server.nio.repository.file.service.FileService;
import com.alibaba.server.nio.repository.user.service.UserService;
import com.alibaba.server.nio.repository.user.service.dto.UserDTO;
import com.alibaba.server.nio.repository.user.service.param.UserQueryParam;
import com.alibaba.server.nio.service.file.model.DownloadResult;
import com.alibaba.server.nio.service.file.model.FileTransport;
import com.alibaba.server.nio.service.file.task.FileDownloadStreamCallable;
import com.alibaba.server.util.LocalTime;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.springframework.util.CollectionUtils;
import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

/**
 * 通道处理器
 *
 * @author spring
 * */

@Slf4j
public abstract class AbstractChannelHandler implements ChannelHandler {
    public static UserService userService = null;
    public static FileService fileService = null;
    static {
        userService = BasicServer.classPathXmlApplicationContext.getBean(UserService.class);
        fileService = BasicServer.classPathXmlApplicationContext.getBean(FileService.class);
    }

    /**
     * 文件下载任务线程集(用于中断线程或是得到下载线程的执行结果)
     * */
    public static final Map<Long, Map<String, Object>> fileDownloadThreadMap = new ConcurrentHashMap<>();

    /**
     *
     * */
    private static final ScheduledExecutorService scheduledExecutorService = new ScheduledThreadPoolExecutor(1);

    /**
     * 文件下载结果线程是否启动
     * */
    private static Boolean isStartup = Boolean.FALSE;

    /**
     * 启动文件下载结果线程：用于处理文件下载线程返回结果
     * */
    private void startFileDownloadLoopThread() {
        isStartup = Boolean.TRUE;
        scheduledExecutorService.scheduleWithFixedDelay(() -> {
            if(CollectionUtils.isEmpty(fileDownloadThreadMap)) {
                return;
            }

            Iterator iterator = fileDownloadThreadMap.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Long, Map<String, Object>> entry = (Map.Entry<Long, Map<String, Object>>)iterator.next();
                Map<String, Object> value = entry.getValue();

                if(!CollectionUtils.isEmpty(value)) {
                    FutureTask<DownloadResult> futureTask = (FutureTask<DownloadResult>) value.get("FUTURE.TASK");
                    try {
                        if(Optional.ofNullable(futureTask).isPresent()) {
                            DownloadResult downloadResult = futureTask.get(10L, TimeUnit.SECONDS);
                            if(downloadResult.getFinished()) {
                                log.info("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] AbstractChannelHandler | --> result = {}, thread = {}", downloadResult.getData(), Thread.currentThread().getName());
                                // 下载任务已完成,释放资源
                                iterator.remove();
                            }
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    } catch (ExecutionException e) {
                        e.printStackTrace();
                    } catch (TimeoutException e) {
                        continue;
                    }
                }
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    /**
     * 添加文件下载任务
     * @param currentDownloadFileMap
     * @param socketChannelContext
     */
    protected void addFileDownloadTask(Map<String, Object> currentDownloadFileMap, SocketChannelContext socketChannelContext) {
        synchronized (isStartup) {
            if(CollectionUtils.isEmpty(fileDownloadThreadMap) && !isStartup) {
                this.startFileDownloadLoopThread();
            }
        }

        Long tag = Long.valueOf(String.valueOf(currentDownloadFileMap.get("TAG")));
        if(!fileDownloadThreadMap.containsKey(tag)) {
            FutureTask<DownloadResult> futureTask = new FutureTask<>(new FileDownloadStreamCallable(currentDownloadFileMap, socketChannelContext));
            Thread thread = new Thread(futureTask, BasicConstant.FILE_DOWNLOAD_THREAD);

            Map<String, Object> taskMap = new HashMap<>();
            taskMap.put("FUTURE.TASK", futureTask);
            taskMap.put("THREAD", thread);

            fileDownloadThreadMap.put(tag, taskMap);

            // 启动文件下载线程
            thread.start();
        }
    }

    /**
     * 校验SocketChannel的状态
     * @Param socketChannel
     * @return boolean
     * */
    public Boolean checkConnected(SocketChannel socketChannel) {
        if(!socketChannel.isConnected()) {
            log.warn("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] AbstractChannelHandler | --> socketChannel is not connected, can not send data , remoteAddress = {}, localAddress = {}",
                NioServerContext.getRemoteAddress(socketChannel), NioServerContext.getLocalAddress(socketChannel));
            NioServerContext.closedAndRelease(socketChannel);
            return Boolean.FALSE;
        }

        return Boolean.TRUE;
    }

    /**
     * 校验文件传输是否应该被终止
     * @param fileTransport
     * @return
     */
    public static void checkFileTransportStop(FileTransport fileTransport) {
        // 获取文件关闭通道类型(上行关闭还是下行关闭)
        String operateType = fileTransport.getOperate().split(";")[1];

        // 查询当前用户的文件传输集合
        UserQueryParam userQueryParam = new UserQueryParam();
        userQueryParam.setUserName(fileTransport.getLaunchUserName());
        UserDTO userDto = (BasicServer.classPathXmlApplicationContext.getBean(UserService.class)).getOnlineUser(userQueryParam);
        Map<String, Object> map = StringUtils.equals(operateType, "UPLOAD")?
            userDto.getUploadFileMap().get(fileTransport.getTag()):userDto.getDownloadFileMap().get(fileTransport.getTag());
        FileChannel fileChannel = (FileChannel) (map.get("FILE_CHANNEL"));
        String fileName = ((File) (map.get("FILE"))).getName();
        if(null != fileChannel) {
            if(StringUtils.equals(operateType, BasicConstant.UPLOAD)) {
                // 关闭文件通道
                try {
                    fileChannel.close();
                    log.info("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] AbstractChannelHandler | --> 关闭文件上传通道(FileChannel)成功, file = {}, thread = {}", fileName, Thread.currentThread().getName());
                } catch (IOException e) {
                    log.info("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] AbstractChannelHandler | --> 关闭文件上传通道(FileChannel)失败, file = {}, thread = {}", fileName, Thread.currentThread().getName());
                    e.printStackTrace();
                }

                // 上传操作删除文件
                File file = (File) (map.get("FILE"));
                if(file.exists()) {
                    file.delete();
                    log.info("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] AbstractChannelHandler | --> 临时上传文件删除成功, file = {}, thread = {}", fileName, Thread.currentThread().getName());
                }

                userDto.getUploadFileMap().remove(fileTransport.getTag());
                log.info("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] AbstractChannelHandler | --> 用户 [{}] 移除上传文件任务数据成功, 剩余任务数 = [{}], file = {}, thread = {}",
                    userDto.getUserName(), userDto.getUploadFileMap().size(), fileName, Thread.currentThread().getName());
            } else {
                // 下载操作中断下载线程
                Thread thread = (Thread) (fileDownloadThreadMap.remove(Long.valueOf(fileTransport.getTag())).get("THREAD"));
                if(!thread.isInterrupted()) {
                    thread.interrupt();
                }
                userDto.getDownloadFileMap().remove(fileTransport.getTag());
                log.info("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] AbstractChannelHandler | --> 用户 [{}] 移除下载文件任务数据成功, 剩余任务数 = [{}], file = {}, thread = {}",
                    userDto.getUserName(), userDto.getDownloadFileMap().size(), fileName, Thread.currentThread().getName());
            }
        }
    }

}
