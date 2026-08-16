package com.alibaba.server.config;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DatabaseConfigurationSecurityTest {

    @Test
    public void databaseCredentialsAreExternalizedFromRepositoryResources() throws Exception {
        String springContext = read("src/main/resources/spring/applicationContext.xml");
        String mybatisConfig = read("src/main/resources/mybatis/mybatis-config.xml");
        String combined = springContext + "\n" + mybatisConfig;

        assertFalse(combined.contains("jdbc:mysql://"));
        // [修改] 用结构性断言锁定资源文件无内置连接信息，不在测试源码重复保存历史凭据。
        assertFalse(combined.contains("<property name=\"username\" value=\""));
        assertFalse(combined.contains("<property name=\"password\" value=\""));
        assertFalse(combined.contains("<environments"));

        assertTrue(springContext.contains("systemEnvironment['NET_SERVER_DB_URL']"));
        assertTrue(springContext.contains("systemEnvironment['NET_SERVER_DB_USERNAME']"));
        assertTrue(springContext.contains("systemEnvironment['NET_SERVER_DB_PASSWORD']"));
    }

    @Test
    public void mybatisDoesNotPrintSqlParametersOrUserRowsToStandardOutput() throws Exception {
        String springContext = read("src/main/resources/spring/applicationContext.xml");
        String coreServer = read("src/main/java/com/alibaba/server/nio/core/server/CoreServer.java");
        String combined = springContext + "\n" + coreServer;

        // [修改] 登录查询包含密码字段，生产配置禁止使用会直接打印 SQL 和结果行的 StdOutImpl。
        assertFalse(combined.contains("org.apache.ibatis.logging.stdout.StdOutImpl"));
        assertFalse(combined.contains("setLogImpl(StdOutImpl.class)"));
        assertTrue(combined.contains("org.apache.ibatis.logging.nologging.NoLoggingImpl"));
        assertTrue(combined.contains("setLogImpl(NoLoggingImpl.class)"));
    }

    private String read(String path) throws Exception {
        Path source = Paths.get(path);
        return new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
    }
}
