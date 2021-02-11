package com.alibaba.server;

import com.alibaba.server.common.OSinfo;
import com.alibaba.server.nio.core.server.NioServerContext;
import com.alibaba.server.util.BasicUtil;
import com.alibaba.server.util.LocalTime;
import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @Auther: spring
 * @Date: 2020/10/3
 * @Pacage_name: com.alibaba.server
 * @Project_Name: net-server
 * @Description: 网络服务服务端管理
 */

@Slf4j
@SuppressWarnings("all")
public class NetServer {

    public static void main( String[] args ) {
        log.info("[" + LocalTime.formatDate(LocalDateTime.now()) + "] App | --> 当前操作系统类型: " + OSinfo.getOSname() + ", 可支持的最大线程数: " + Runtime.getRuntime().availableProcessors());

        // 1、启动Nio服务
        NioServerContext.startupServerContext();

        // 2、启动Netty服务
        //GlobalNettyServer.startServerBootstrap();

        //test();
    }

    private static void test() {
        List<User> list = Lists.newArrayList();
        User user1 = new User();
        user1.setUserName("spring");
        user1.setAge(15);

        User user11 = new User();
        user11.setUserName("spring");
        user11.setAge(20);

        User user112 = new User();
        user112.setUserName("spring");
        user112.setAge(5);

        User user2 = new User();
        user2.setUserName("spring2");
        user2.setAge(19);

        User user3 = new User();
        user3.setUserName("spring3");
        user3.setAge(7);
        list.add(user1);list.add(user11);list.add(user112);
        list.add(user2);
        list.add(user3);

        List<User> tempList = list.stream().sorted(Comparator.comparing(user -> user.getAge())).collect(Collectors.toList());
        Map<String, List<User>> profileMap = list.stream().sorted(Comparator.comparing(User::getAge).reversed()).collect(
            Collectors.groupingBy(User::getUserName));
        System.out.println(profileMap);
    }

    private static class User {
        private String userName;
        private int age;

        public String getUserName() {
            return userName;
        }

        public void setUserName(String userName) {
            this.userName = userName;
        }

        public int getAge() {
            return age;
        }

        public void setAge(int age) {
            this.age = age;
        }
    }
}
