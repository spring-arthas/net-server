package com.alibaba.server.nio.core.server;

import com.alibaba.server.common.BasicConstant;
import com.alibaba.server.nio.handler.event.concret.ReadEventHandler;
import com.alibaba.server.nio.repository.user.service.UserService;
import com.alibaba.server.nio.repository.user.service.dto.UserDTO;
import com.alibaba.server.nio.repository.user.service.param.UserUpdateParam;
import com.alibaba.server.util.LocalTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.channels.*;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * @Auther: YSFY
 * @Date: 2020-11-14 17:09
 * @Pacage_name: com.alibaba.server.nio.core
 * @Project_Name: net-server
 * @Description: 服务上下文管理器
 */

@Slf4j
@SuppressWarnings("all")
public class NioServerContext {

    private static final Object lock = new Object();
    
    /**
     * 限速恢复调度器
     * 用于在 ReadEventHandler 暂停 OP_READ 后，定时恢复事件
     * 线程数设为4以支持多个并发连接的限速恢复任务
     */
    private static final java.util.concurrent.ScheduledExecutorService rateLimitScheduler = 
            java.util.concurrent.Executors.newScheduledThreadPool(4, new java.util.concurrent.ThreadFactory() {
                private final java.util.concurrent.atomic.AtomicInteger threadNumber = new java.util.concurrent.atomic.AtomicInteger(1);
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "RATE-LIMIT-SCHEDULER-" + threadNumber.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                }
            });

    /**
     * 获取限速调度器
     */
    public static java.util.concurrent.ScheduledExecutorService getRateLimitScheduler() {
        return rateLimitScheduler;
    }

    /**
     * 启动基础服务以及核心服务
     * 
     * @return
     */
    public static void startupServerContext() {
        try {
            BasicServer.startupBasicServer();

            CoreServer.startupCoreServer();

            // 3、追加额外处理
            // CoreServer.appendHandler();
        } catch (Exception e) {
            log.error("NioServerContext: 服务启动失败, error = {}", ExceptionUtils.getStackTrace);
        }
    }

    /**
     * 创建Selector
     * 
     * @return selector
     */
    public static Selector openSelector() throws IOException {
        return Selector.open();
    }

    /**
     * 根据不同服务名称获取其对应的Selector
     * 
     * @param selectorName
     * @return selector
     */
    public static Selector getSelector(String selectorName) {
        Map<String, Object> cacheMap = BasicServer.getMap();
        if (!cacheMap.isEmpty() && cacheMap.containsKey(BasicConstant.SELECTOR)) {
            Map<String, Object> assignMap = (Map) cacheMap.get(BasicConstant.SELECTOR);
            if (!assignMap.isEmpty() && assignMap.containsKey(selectorName)) {
                return (Selector) ((Map) assignMap.get(selectorName)).get(selectorName);
            }
        }

        return null;
    }

    /**
     * 根据key获取配置文件数据
     * 
     * @param param
     * @return object
     */
    public static String getValue(String param) {
        Map<String, Object> cacheMap = BasicServer.getMap();
        if (!cacheMap.isEmpty() && cacheMap.containsKey(param)) {
            return cacheMap.get(param).toString();
        }

        return "";
    }

    /**
     * 获取当前请求的服务端端口号，根据端口号判断具体属于哪类请求
     *
     * @param selectionKey
     * @return
     * @throws IOException
     */
    public static String getPort(SelectionKey selectionKey) throws IOException {
        return String.valueOf(
                ((InetSocketAddress) ((ServerSocketChannel) selectionKey.channel()).getLocalAddress()).getPort());
    }

    /**
     * 根据SocketChannel获取远程连接信息(ip:port)
     * 
     * @param socketChannel
     * @return
     */
    public static String getRemoteAddress(SocketChannel socketChannel) {
        String ip = "";
        Integer port = 0;
        try {
            synchronized (lock) {
                ip = ((InetSocketAddress) socketChannel.getRemoteAddress()).getAddress().getHostAddress();
                port = ((InetSocketAddress) socketChannel.getRemoteAddress()).getPort();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return (ip + ":" + port);
    }

    /**
     * 根据SocketChannel获取本地连接信息(ip:port)
     * 
     * @param socketChannel
     * @return
     */
    public static String getLocalAddress(SocketChannel socketChannel) {
        String ip = "";
        Integer port = 0;
        try {
            ip = ((InetSocketAddress) socketChannel.getLocalAddress()).getAddress().getHostAddress();
            port = ((InetSocketAddress) socketChannel.getLocalAddress()).getPort();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return (ip + ":" + port);
    }

    /**
     * 获取服务端本地地址
     * 
     * @param socketChannel
     * @return
     */
    public static String getServerLocalAddress(ServerSocketChannel serverSocketChannel) {
        String ip = "";
        Integer port = 0;
        try {
            ip = ((InetSocketAddress) serverSocketChannel.getLocalAddress()).getAddress().getHostAddress();
            port = ((InetSocketAddress) serverSocketChannel.getLocalAddress()).getPort();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return (ip + ":" + port);
    }

    /**
     * Selector 可选事件注册
     * 
     * @param socketChannel
     * @param selector
     * @param opt
     * @return
     * @throws IOException
     */
    public static SelectionKey EventRegister(SocketChannel socketChannel, Selector selector, final int opt)
            throws IOException {
        if (!selector.isOpen()) {
            log.error(
                    "[ " + LocalTime.formatDate(LocalDateTime.now())
                            + " ] NioServerContext | --> Selector is not open yet, threadName = {}",
                    Thread.currentThread().getName());
            return null;
        }

        socketChannel.configureBlocking(false);
        selector.wakeup();
        return socketChannel.register(selector, opt);
    }

    /**
     * 释放通道资源
     * @param socketChannel
     * @return Boolean 关闭结果
     */
    public static Boolean closedAndRelease(SocketChannel socketChannel) {
        if (!Optional.ofNullable(socketChannel).isPresent()) {
            return;
        }
        try {
            String remoteAddress = NioServerContext.getRemoteAddress(socketChannel);
            String localAddress = NioServerContext.getLocalAddress(socketChannel);
            // 1、清理该远程连接对应的上传任务
            com.alibaba.server.nio.service.file.handler.FileUploadHandler.cleanupConnection(remoteAddress);
            // 2、关闭socketChannel，将会发送流截至符 -1到客户端
            Socket socket = socketChannel.socket();
            if (!socket.isClosed()) {
                socketChannel.shutdownInput();
                socketChannel.shutdownOutput();
                socketChannel.close();
            }
            log.info("NioServerContext: 服务端socketChannel关闭成功, 资源已释放, 本次通道连接信息：remoteAddress = {}, localAddress = {}, thread = {}",
                    remoteAddress, localAddress, Thread.currentThread().getName());
        } catch (Exception e) {
             log.error("NioServerContext: 服务端通道socketChannel资源释放出现异常, 通道信息 = {}, error = {}",
                JSON.toJSONString(socketChannel), 
                ExceptionUtils.get);
        } finally {
            
        }
    }
    /**
     * 获取IOC容器对象
     * 
     * @param cls
     * @return
     */
    public static Object getObjectByType(Class cls) {
        ClassPathXmlApplicationContext context = BasicServer.classPathXmlApplicationContext;
        if (Optional.ofNullable(context).isPresent()) {
            return context.getBean(cls);
        }

        return null;
    }

    /**
     * 获取 FileService 实例
     * 
     * @return FileService
     */
    public static com.alibaba.server.nio.repository.file.service.FileService getFileService() {
        return (com.alibaba.server.nio.repository.file.service.FileService) getObjectByType(
                com.alibaba.server.nio.repository.file.service.FileService.class);
    }

    /**
     * SocketChannel IOException异常重连
     * 
     * @param socketChannel
     * @return Boolean
     */
    public static Boolean reConnected(SocketChannel socketChannel) {
        if (socketChannel.isConnected()) {
            // 如果由于网络抖动，可能直接又连接上，则直接返回true
            return Boolean.TRUE;
        }

        Integer index = 1;
        Boolean reconnected = Boolean.FALSE;
        Integer reconnectedCount = Integer.valueOf(NioServerContext.getValue(BasicConstant.SOCKET_RECONNECTED_COUNT));
        // 尝试重连
        while (index <= reconnectedCount) {
            log.info(
                    "[" + Thread.currentThread().getName()
                            + " ] NioServerContext | --> {} reconnected..., address = {}, thread = {}",
                    index, NioServerContext.getLocalAddress(socketChannel), Thread.currentThread().getName());

            try {
                socketChannel.connect((InetSocketAddress) socketChannel.getRemoteAddress());
                if (!socketChannel.isConnected()) {
                    // 此处连接不上，直接等待socket超时
                    while (socketChannel.finishConnect()) {
                        reconnected = Boolean.TRUE;
                        break;
                    }
                } else {
                    reconnected = Boolean.TRUE;
                }
            } catch (IOException e) {
                if (e instanceof SocketTimeoutException) {
                    // socket连接超时异常,尝试下次连接
                    index = index + 1;
                    continue;
                }
            }

            if (Boolean.TRUE.equals(reconnected)) {
                log.info(
                        "[" + Thread.currentThread().getName()
                                + " ] NioServerContext | --> {} reconnected success, address = {}, thread = {}",
                        index, NioServerContext.getLocalAddress(socketChannel), Thread.currentThread().getName());
                break;
            }
        }

        // 判断如果达到最大重连次数后还是无法连接上，则关闭当前SocketChannel文件描述符，释放资源
        if (index > reconnectedCount && Optional.ofNullable(socketChannel).isPresent()) {
            try {
                socketChannel.shutdownInput();
                socketChannel.shutdownOutput();
                socketChannel.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        return reconnected;
    }

    /**
     * 启动IOC容器
     */
    public static void startupIocContainer() throws IOException {
        BasicServer.classPathXmlApplicationContext = new ClassPathXmlApplicationContext(
                "spring/applicationContext.xml");
        log.info("net server IOC container初始化成功, threadName = {}", Thread.currentThread().getName());
    }

    /**
     * 获取BasicServer Map中的元素
     *
     * @param key      集合大key
     * @param goOn     是否对大类key返回的value节序遍历,基本为Map类型才会进行继续遍历
     * @param childKey 当goOn为true时指定二次遍历时的key
     * @return optional
     */
    public static Optional<Object> getAssignValue(String key, Boolean goOn, String childKey) {
        Map map = BasicServer.getMap();
        if (CollectionUtils.isEmpty(map) || !map.containsKey(key)) {
            return null;
        }

        Object obj = map.get(key);
        if (!Optional.ofNullable(obj).isPresent()) {
            return null;
        }

        if (obj instanceof java.util.Map) {
            if (!goOn) {
                return Optional.of(obj);
            }

            Map<String, Object> innerMap = (Map<String, Object>) obj;
            if (CollectionUtils.isEmpty(innerMap) || !innerMap.containsKey(childKey)) {
                return null;
            }

            return Optional.of(innerMap.get(childKey));
        }

        if (obj instanceof java.util.List || obj instanceof java.util.List || obj instanceof java.lang.String) {
            return Optional.of(obj);
        }

        return null;
    }
}
