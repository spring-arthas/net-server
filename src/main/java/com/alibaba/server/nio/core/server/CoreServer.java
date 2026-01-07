package com.alibaba.server.nio.core.server;

import com.alibaba.server.common.BasicConstant;
import com.alibaba.server.nio.acceptor.MainChatAcceptor;
import com.alibaba.server.nio.acceptor.MainFileDownloadAcceptor;
import com.alibaba.server.nio.acceptor.MainFileUploadAcceptor;
import com.alibaba.server.nio.core.page.PaginatedListObjFactory;
import com.alibaba.server.nio.core.page.PaginatedListWrapperFactory;
import com.alibaba.server.nio.core.repository.*;
import com.alibaba.server.nio.handler.event.EventHandlerContext;
import com.alibaba.server.nio.handler.event.concret.AcceptorEventHandler;
import com.alibaba.server.nio.handler.event.concret.ConnectEventHandler;
import com.alibaba.server.nio.handler.event.concret.ReadEventHandler;
import com.alibaba.server.nio.handler.event.concret.WriteEventHandler;
import com.alibaba.server.nio.model.constant.ChannelEventModelEnum;
import com.alibaba.server.nio.selector.MainChatSelector;
import com.alibaba.server.nio.selector.MainFileSelector;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.logging.stdout.StdOutImpl;
import org.apache.ibatis.session.Configuration;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * @Auther: YSFY
 * @Date: 2020-11-14 16:33
 * @Pacage_name: com.alibaba.server.nio.core
 * @Project_Name: net-server
 * @Description: 核心启动服务
 */

@Slf4j
@SuppressWarnings("all")
public class CoreServer {

    /**
     * 启动核心配置服务(创建事件处理链, 创建Selector，Acceptor)
     *
     * @throws IOException
     */
    public static void startupCoreServer() throws IOException {

        // 1、启动mybatis, 创建NIO Selector线程(用于监听事件)以及Server NIO Accepter线程(用于接收客户端socket连接)
        startup();

        // 2、启动事件处理链
        EventHandlerContext.getEventHandlerContext()
                .addEventHandler(new AcceptorEventHandler())
                .addEventHandler(new ConnectEventHandler())
                .addEventHandler(new ReadEventHandler())
                .addEventHandler(new WriteEventHandler());
    }

    /**
     * 启动
     *
     * @throws IOException
     */
    private static void startup() throws IOException {
        // 启动Spring IOC容器
        NioServerContext.startupIocContainer();
        // 启动聊天和文件多路复用选择器
        startupSelector();
        // 创建聊天、文件上传和下载的Acceptor线程，即用于监听客户端连接
        startupMainAcceptor();
    }

    /**
     * 创建Selector
     * 
     * @throws IOException
     */
    private static void startupSelector() throws IOException {
        create(BasicConstant.SELECTOR, BasicConstant.NIO_SERVER_MAIN_CORE_CHAT_SELECTOR, new MainChatSelector(),
                Boolean.TRUE);
        create(BasicConstant.SELECTOR, BasicConstant.NIO_SERVER_MAIN_CORE_FILE_SELECTOR, new MainFileSelector(),
                Boolean.TRUE);
        // create(BasicConstant.SELECTOR,
        // BasicConstant.NIO_SERVER_MAIN_CORE_WEBSOCKET_SELECTOR, new
        // MainWebSocketSelector(), Boolean.TRUE);
    }

    /**
     * 创建MainAcceptor线程
     * 
     * @throws IOException
     */
    private static void startupMainAcceptor() throws IOException {
        create(ChannelEventModelEnum.TEXT_TRANSMISSION.getName(),
                BasicConstant.NIO_SERVER_MAIN_CORE_CHAT_ACCEPTOR,
                new MainChatAcceptor(BasicConstant.NIO_SERVER_MAIN_CORE_CHAT_ACCEPTOR), Boolean.FALSE);
        create(ChannelEventModelEnum.FILE_UPLOAD.getName(),
                BasicConstant.NIO_SERVER_MAIN_CORE_FILE_UPLOAD_ACCEPTOR,
                new MainFileUploadAcceptor(BasicConstant.NIO_SERVER_MAIN_CORE_FILE_UPLOAD_ACCEPTOR), Boolean.FALSE);
        create(ChannelEventModelEnum.FILE_DOWNLOAD.getName(),
                BasicConstant.NIO_SERVER_MAIN_CORE_FILE_DOWNLOAD_ACCEPTOR,
                new MainFileDownloadAcceptor(BasicConstant.NIO_SERVER_MAIN_CORE_FILE_DOWNLOAD_ACCEPTOR), Boolean.FALSE);
        // create(BasicConstant.ACCEPTOR,
        // BasicConstant.NIO_SERVER_MAIN_CORE_WEBSOCKET_ACCEPTOR, new
        // MainWebSocketAcceptor(BasicConstant.NIO_SERVER_MAIN_CORE_WEBSOCKET_ACCEPTOR),
        // Boolean.FALSE);
    }

    /**
     * 创建Selector和Acceptor
     * 
     * @param commonName   SELECTOR OR ACCEPTOR
     * @param concreteName
     * @param runnable
     * @param b
     * @throws IOException
     */
    private static void create(String commonName, String concreteName, Runnable runnable, Boolean b)
            throws IOException {
        if (!BasicServer.getMap().containsKey(commonName)) {
            BasicServer.getMap().put(commonName, new HashMap<String, Object>());
        }

        Map<String, Object> commonMap = (Map<String, Object>) BasicServer.getMap().get(commonName);
        if (!commonMap.containsKey(concreteName)) {
            Map<String, Object> map = new HashMap<>(8);
            commonMap.put(concreteName, map);

            if (b.booleanValue()) {
                map.put(concreteName, NioServerContext.openSelector());
                map.put(BasicConstant.SELECTOR_RUNNABLE, runnable);
            } else {
                Thread thread = new Thread(runnable, concreteName + ".ACCEPTOR.THREAD");
                map.put(BasicConstant.ACCEPTOR_THREAD, thread);
                map.put(BasicConstant.ACCEPTOR_RUNNABLE, runnable);
                thread.start();

                return;
            }

            // 2、启动Selector线程
            new Thread(runnable, concreteName + ".THREAD").start();
        }
    }

    /**
     * 追加额外处理：1、处理mybatis持久化SqlSessionFactory
     * 
     * @throws IOException
     */
    public static void appendHandler() throws IOException {
        SqlSessionFactoryBean sqlSessionFactoryBean = BasicServer.classPathXmlApplicationContext
                .getBean(SqlSessionFactoryBean.class);
        Class<?> beanType = BasicServer.classPathXmlApplicationContext.getType("sqlSessionFactory");
        try {
            Field field = beanType.getDeclaredField("configuration");
            field.setAccessible(true);

            // 资源映射 @MapMethod
            MapConfiguration config = new MapConfiguration();
            // resultMap自动下划线转驼峰
            config.setMapUnderscoreToCamelCase(true);
            // 针对PaginatedList实现的Mapper返回值转换
            config.setObjectFactory(new PaginatedListObjFactory());
            config.setObjectWrapperFactory(new PaginatedListWrapperFactory());
            config.setLogImpl(StdOutImpl.class);
            sqlSessionFactoryBean.setConfiguration(config);

            field.set(sqlSessionFactoryBean, Configuration.class);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            e.printStackTrace();
        }

        // 分页拦截器 @PageQuery
        // sqlSessionFactoryBean.setPlugins(new PageInterceptor(), new
        // IdGeneratorInterceptor(idGenerator()));
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        sqlSessionFactoryBean.setMapperLocations(resolver.getResources("classpath*:/mappers/*.xml"));
    }
}
