package com.alibaba.server.nio.core.server;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

// [修改] 运行期 TLS 监听故障必须让唯一的 Java 进程非零退出。
public class NioServerContextTlsFailureTest {

    @Test
    public void exitsProcessWhenEmbeddedTlsListenerFails() throws Exception {
        String classPath = System.getProperty(
                "surefire.test.class.path",
                System.getProperty("java.class.path"));
        Process process = new ProcessBuilder(
                javaExecutable().toString(),
                "-cp",
                classPath,
                FailureProbe.class.getName())
                .redirectErrorStream(true)
                .start();

        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("TLS 监听故障后测试 Java 进程没有退出");
        }
        String output = readAll(process.getInputStream());

        assertEquals(output, 1, process.exitValue());
        assertTrue(output, output.contains("TLS Gateway 监听发生致命故障"));
    }

    public static final class FailureProbe {
        private FailureProbe() {
        }

        public static void main(String[] args) {
            NioServerContext.handleTlsGatewayFailure(
                    new IOException("listener failed for test"));
            throw new AssertionError("生命周期处理器返回后进程仍存活");
        }
    }

    private static Path javaExecutable() {
        Path javaHome = Paths.get(System.getProperty("java.home"));
        Path direct = javaHome.resolve("bin/java");
        if (Files.isExecutable(direct)) {
            return direct;
        }
        Path parent = javaHome.getParent();
        if (parent != null && Files.isExecutable(parent.resolve("bin/java"))) {
            return parent.resolve("bin/java");
        }
        throw new AssertionError("当前 JDK 未找到 java: " + javaHome);
    }

    private static String readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1_024];
        int length;
        while ((length = input.read(buffer)) >= 0) {
            output.write(buffer, 0, length);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
}
