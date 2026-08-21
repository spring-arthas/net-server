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
import java.io.IOException;

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
        String commandLinePublicIp = resolveCommandLinePublicIp(args);
        if (commandLinePublicIp != null) {
            // [修改] Windows 直接使用 java -jar 启动时，命令行 IP 参与媒体地址和 TLS 地址解析。
            System.setProperty("NET_SERVER_PUBLIC_IP", commandLinePublicIp);
        }
        log.info("[" + LocalTime.formatDate(LocalDateTime.now()) + "] App | --> 当前操作系统类型: " + OSinfo.getOSname() + ", 可支持的最大线程数: " + Runtime.getRuntime().availableProcessors());

        try {
            // 1、启动Nio服务
            NioServerContext.startupServerContext();
        } catch (IllegalStateException exception) {
            // [修改] TLS 证书、端口或后端启动失败时，单进程部署必须返回非零退出码。
            showStartupFailureDialog(exception);
            System.exit(1);
        }

        // 2、启动Netty服务
        //GlobalNettyServer.startServerBootstrap();

        //test();
    }

    static String resolveCommandLinePublicIp(String[] args) {
        if (args == null) {
            return null;
        }
        for (int index = 0; index < args.length; index++) {
            String argument = args[index];
            if (argument == null) {
                continue;
            }
            if (argument.startsWith("--server-ip=")) {
                return nonBlank(argument.substring("--server-ip=".length()));
            }
            if ("--server-ip".equals(argument) && index + 1 < args.length) {
                return nonBlank(args[index + 1]);
            }
            if (index == 0 && !argument.startsWith("-")) {
                return nonBlank(argument);
            }
        }
        return null;
    }

    private static String nonBlank(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    // [修改] Finder 启动没有终端窗口，端口冲突时必须给出可见原因，避免用户误以为应用闪退。
    private static void showStartupFailureDialog(Throwable exception) {
        if (!OSinfo.isMacOS() && !OSinfo.isMacOSX()) {
            return;
        }

        String message = hasPortConflict(exception)
                ? "服务端口已被其他 Net Server 进程占用，请先关闭正在运行的服务后重试。"
                : "服务启动失败，请从终端启动 Net Server 查看详细日志。";
        String appleScript = "display alert "
                + appleScriptString("Net Server 启动失败")
                + " message "
                + appleScriptString(message)
                + " as critical";
        try {
            Process process = new ProcessBuilder("/usr/bin/osascript", "-e", appleScript).start();
            process.waitFor();
        } catch (IOException exceptionWhileShowingDialog) {
            log.warn("无法显示 Net Server 启动失败提示, error={}", exceptionWhileShowingDialog.getMessage());
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean hasPortConflict(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof java.net.BindException
                    || "Address already in use".equals(current.getMessage())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String appleScriptString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
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
