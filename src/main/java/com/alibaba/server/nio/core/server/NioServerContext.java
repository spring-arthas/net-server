package com.alibaba.server.nio.core.server;

import com.alibaba.server.common.BasicConstant;
import com.alibaba.server.nio.core.repository.IdGeneratorInterceptor;
import com.alibaba.server.nio.core.repository.PageInterceptor;
import com.alibaba.server.nio.handler.event.concret.ReadEventHandler;
import com.alibaba.server.nio.repository.user.service.UserService;
import com.alibaba.server.nio.repository.user.service.dto.UserDTO;
import com.alibaba.server.nio.repository.user.service.param.UserUpdateParam;
import com.alibaba.server.util.LocalTime;
import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.channels.*;
import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
     * 启动基础服务以及核心服务
     * @return
     * */
    public static void startupServerContext() {
        try {
            BasicServer.startupBasicServer();

            CoreServer.startupCoreServer();

            // 3、追加额外处理
            //CoreServer.appendHandler();
        } catch (Exception e) {
            log.error("[" + LocalTime.formatDate(LocalDateTime.now()) + "] NioServerContext | --> 服务启动失败, error = {}", e.getLocalizedMessage());
            e.printStackTrace();
        }
    }

    /**
     * 创建Selector
     * @return selector
     * */
    public static Selector openSelector() throws IOException {
        return Selector.open();
    }

    /**
     * 根据不同服务名称获取其对应的Selector
     * @param selectorName
     * @return selector
     * */
    public static Selector getSelector(String selectorName) {
        Map<String, Object> cacheMap = BasicServer.getMap();
        if(!cacheMap.isEmpty() && cacheMap.containsKey(BasicConstant.SELECTOR)) {
            Map<String, Object> assignMap = (Map) cacheMap.get(BasicConstant.SELECTOR);
            if(!assignMap.isEmpty() && assignMap.containsKey(selectorName)) {
                return (Selector) ((Map) assignMap.get(selectorName)).get(selectorName);
            }
        }

        return null;
    }

    /**
     * 根据key获取配置文件数据
     * @param param
     * @return object
     * */
    public static String getValue(String param) {
        Map<String, Object> cacheMap = BasicServer.getMap();
        if(!cacheMap.isEmpty() && cacheMap.containsKey(param)) {
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
        return String.valueOf(((InetSocketAddress) ((ServerSocketChannel) selectionKey.channel()).getLocalAddress()).getPort());
    }

    /**
     * 根据SocketChannel获取远程连接信息(ip:port)
     *
     * @param socketChannel
     * @return
     * */
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
     * */
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
     * @param socketChannel
     * @param selector
     * @param opt
     * @return
     * @throws IOException
     * */
    public static SelectionKey EventRegister(SocketChannel socketChannel, Selector selector, final int opt) throws IOException {
        if(!selector.isOpen()) {
            log.error("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] NioServerContext | --> Selector is not open yet, threadName = {}", Thread.currentThread().getName());
            return null;
        }

        socketChannel.configureBlocking(false);
        selector.wakeup();
        return socketChannel.register(selector, opt);
    }

    /**
     * 发送消息
     * @param socketChannel
     * @param message
     * @return
     * */
    public static void sendMessage(SocketChannel socketChannel, String selectorName, Object o) throws IOException {
        /*try {
            if(!socketChannel.isConnected()) {
                log.warn("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] CoreServerContext | --> socketChannel is not connected, can not send data , remoteAddress = {}, localAddress = {}",
                    CoreServerContext.getRemoteAddress(socketChannel), CoreServerContext.getLocalAddress(socketChannel));
                CoreServerContext.closed(socketChannel);
            }

            Selector selector = ((Selector) ((Map) BasicServer.getMap().get(selectorName)).get(selectorName));
            selector.wakeup();

            // 1、为当前通道注册写事件，并返回写事件可选择键，并设置一个附件
            CoreServerContext.EventRegister(socketChannel, selector, SelectionKey.OP_WRITE).attach(o);
        } catch (IOException e) {
            throw e;
        }*/
    }

    /**
     * 关闭socketChannel
     * @param socketChannel
     * @param subReactor
     * @return Boolean 关闭结果
     * */
    public static Boolean closedAndRelease(SocketChannel socketChannel) {
        String remoteAddress = "";
        try {
            if(Optional.ofNullable(socketChannel).isPresent()) {
                remoteAddress = NioServerContext.getRemoteAddress(socketChannel);
                String localAddress = NioServerContext.getLocalAddress(socketChannel);

                // 1、关闭socketChannel，将会发送流截至符 -1到客户端
                Socket socket = socketChannel.socket();
                if(!socket.isClosed()) {
                    socketChannel.shutdownInput();
                    socketChannel.shutdownOutput();
                    socketChannel.close();
                }
                log.warn("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] NioServerContext | --> socketChannel closed local connected success, remoteAddress = {}, localAddress = {}, thread = {}",
                    remoteAddress, localAddress, Thread.currentThread().getName());
            }
        } catch (Exception e) {
            if(e instanceof ClosedChannelException) {
                log.warn("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] NioServerContext | --> socketChannel shutdown input or output warning, channel is closed, address = {}, message = {}",
                    NioServerContext.getLocalAddress(socketChannel), e.getMessage());
            }

            if(e instanceof NotYetConnectedException) {
                log.warn("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] NioServerContext | --> socketChannel is not connect yet, address = {}, message = {}",
                    NioServerContext.getLocalAddress(socketChannel), e.getMessage());
            }

            e.printStackTrace();
        } finally {
            return handleSubReactor(remoteAddress, Boolean.TRUE);
        }
    }

    /**
     * 下线操作
     * @param remoteAddress
     * @param isCloseSubReactor 是否关闭subReactor线程
     * @return
     */
    public static Boolean handleSubReactor(String remoteAddress, Boolean isCloseSubReactor) {
        // 1、清空当前通道缓存数据
        ReadEventHandler.channelDataMap.remove(remoteAddress);

        // 2、内存移除用户,获取到用户id；
        UserDTO userDto = (UserDTO)((Map) BasicServer.getMap().get(BasicConstant.USER)).remove(remoteAddress);

        // 3、更新数据库用户状态为登出
        if(Optional.ofNullable(userDto).isPresent()) {
            UserUpdateParam userUpdateParam = new UserUpdateParam();
            userUpdateParam.setId(userDto.getId());
            userUpdateParam.setStatus("2");
            ((UserService) BasicServer.classPathXmlApplicationContext.getBean(UserService.class)).update(userUpdateParam);

            log.info("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] NioServerContext | --> memory user cache remove success and update db user login status success, remoteAddress = {}, thread = {}",
                remoteAddress, Thread.currentThread().getName());
        }

        if(!isCloseSubReactor) {
            return Boolean.TRUE;
        }
        // 4、移除用户对应的subReactor线程，并中断线程
        Map<String, Object> subReactorMap = ((Map) BasicServer.getMap().get(BasicConstant.GLOBAL_MAIN_REACTOR));
        if(!CollectionUtils.isEmpty(subReactorMap)) {
            Map<String, Object> map = ((Map) subReactorMap.remove(remoteAddress));
            if(!CollectionUtils.isEmpty(map)) {
                Thread thread = (Thread) map.get(BasicConstant.THREAD);
                // 中断线程
                thread.interrupt();
                log.info("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] NioServerContext | --> subReactor user cache remove success, surplus online connections = {}, remoteAddress = {}, thread = {}",
                    subReactorMap.size(), remoteAddress, Thread.currentThread().getName());
            }
        }

        return Boolean.TRUE;
    }

    /**
     * 获取IOC容器对象
     * @param cls
     * @return
     * */
    public static Object getObjectByType(Class cls) {
        ClassPathXmlApplicationContext context = BasicServer.classPathXmlApplicationContext;
        if(Optional.ofNullable(context).isPresent()) {
            return context.getBean(cls);
        }

        return null;
    }

    /**
     * SocketChannel IOException异常重连
     * @param socketChannel
     * @return Boolean
     * */
    public static Boolean reConnected(SocketChannel socketChannel) {
        if(socketChannel.isConnected()) {
            // 如果由于网络抖动，可能直接又连接上，则直接返回true
            return Boolean.TRUE;
        }

        Integer index = 1;
        Boolean reconnected = Boolean.FALSE;
        Integer reconnectedCount = Integer.valueOf(NioServerContext.getValue(BasicConstant.SOCKET_RECONNECTED_COUNT));
        // 尝试重连
        while (index <= reconnectedCount) {
            log.info("[" + Thread.currentThread().getName() + " ] NioServerContext | --> {} reconnected..., address = {}, thread = {}", index, NioServerContext.getLocalAddress(socketChannel), Thread.currentThread().getName());

            try {
                socketChannel.connect((InetSocketAddress) socketChannel.getRemoteAddress());
                if(!socketChannel.isConnected()){
                    // 此处连接不上，直接等待socket超时
                    while (socketChannel.finishConnect()) {
                        reconnected = Boolean.TRUE;
                        break;
                    }
                } else {
                    reconnected = Boolean.TRUE;
                }
            } catch (IOException e) {
                if(e instanceof SocketTimeoutException) {
                    // socket连接超时异常,尝试下次连接
                    index = index + 1;
                    continue;
                }
            }

            if(Boolean.TRUE.equals(reconnected)) {
                log.info("[" + Thread.currentThread().getName() + " ] NioServerContext | --> {} reconnected success, address = {}, thread = {}", index, NioServerContext.getLocalAddress(socketChannel), Thread.currentThread().getName());
                break;
            }
        }

        // 判断如果达到最大重连次数后还是无法连接上，则关闭当前SocketChannel文件描述符，释放资源
        if(index > reconnectedCount && Optional.ofNullable(socketChannel).isPresent()) {
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
     * */
    public static void startupIocContainer() throws IOException {
        BasicServer.classPathXmlApplicationContext = new ClassPathXmlApplicationContext("spring/applicationContext.xml");
        log.info("[ " + LocalTime.formatDate(LocalDateTime.now()) + " ] NioServerContext | --> net server IOC container init sucess, threadName = {}", Thread.currentThread().getName());
    }

    /**
     * 获取BasicServer Map中的元素
     *
     * @param key  集合大key
     * @param goOn 是否对大类key返回的value节序遍历,基本为Map类型才会进行继续遍历
     * @param childKey 当goOn为true时指定二次遍历时的key
     * @return optional
     * */
    public static Optional<Object> getAssignValue(String key, Boolean goOn, String childKey) {
        Map map = BasicServer.getMap();
        if(CollectionUtils.isEmpty(map) || !map.containsKey(key)) {
            return null;
        }

        Object obj = map.get(key);
        if(!Optional.ofNullable(obj).isPresent()) {
            return null;
        }

        if(obj instanceof java.util.Map) {
            if(!goOn) {
                return Optional.of(obj);
            }

            Map<String, Object> innerMap = (Map<String, Object>) obj;
            if(CollectionUtils.isEmpty(innerMap) || !innerMap.containsKey(childKey)) {
                return null;
            }

            return Optional.of(innerMap.get(childKey));
        }

        if(obj instanceof java.util.List || obj instanceof java.util.List || obj instanceof java.lang.String) {
            return Optional.of(obj);
        }

        return null;
    }
}
