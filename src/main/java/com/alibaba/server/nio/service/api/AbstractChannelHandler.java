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

}
