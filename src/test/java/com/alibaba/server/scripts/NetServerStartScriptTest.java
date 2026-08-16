package com.alibaba.server.scripts;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class NetServerStartScriptTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void startsWithoutJdwpOnMacOsBash() throws Exception {
        ScriptResult result = runScript(false);

        // [修改] 精确锁定参数顺序，避免 runtime Jar 被放到 -jar 前面仍误判通过。
        assertEquals(result.output, 0, result.exitCode);
        assertEquals("-jar\n" + result.runtimeJar + "\n", result.output);
    }

    @Test
    public void startsWithJdwpBeforeJarArguments() throws Exception {
        ScriptResult result = runScript(true);

        // [修改] 调试参数必须位于 -jar 前面，保证 Java 8 正确识别 JVM 参数。
        assertEquals(result.output, 0, result.exitCode);
        assertEquals(
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=18181\n"
                        + "-jar\n"
                        + result.runtimeJar
                        + "\n",
                result.output);
    }

    @Test
    public void startsJavaWhenPublicIpIsNotConfigured() throws Exception {
        ScriptResult result = runScript(false, null, KeyStoreMode.EXISTS);

        assertEquals(result.output, 0, result.exitCode);
        assertTrue(result.javaInvoked);
    }

    @Test
    public void startsJavaWhenTlsEnvironmentIsNotConfigured() throws Exception {
        ScriptResult result = runScript(false, null, KeyStoreMode.MISSING_VARIABLE);

        assertEquals(result.output, 0, result.exitCode);
        assertTrue(result.javaInvoked);
    }

    @Test
    public void startsJavaWhenConfiguredKeyStoreDoesNotExistYet() throws Exception {
        ScriptResult result = runScript(false, "172.21.32.64", KeyStoreMode.NONEXISTENT);

        assertEquals(result.output, 0, result.exitCode);
        assertTrue(result.javaInvoked);
    }

    private ScriptResult runScript(boolean jdwp) throws Exception {
        return runScript(jdwp, "172.21.32.64", KeyStoreMode.EXISTS);
    }

    private ScriptResult runScript(
            boolean jdwp,
            String publicIp,
            KeyStoreMode keyStoreMode) throws Exception {
        Path repositoryRoot = new File(".").toPath().toAbsolutePath().normalize();
        String sourceScript = new String(
                Files.readAllBytes(repositoryRoot.resolve("scripts/run-net-server-zulu8.sh")),
                StandardCharsets.UTF_8);

        Path isolatedRoot = temporaryFolder.newFolder("isolated-net-server").toPath();
        Files.createDirectories(isolatedRoot.resolve("target"));
        Files.write(isolatedRoot.resolve("target/net-server-1.0-SNAPSHOT.jar"), new byte[]{0});

        Path fakeJavaHome = temporaryFolder.newFolder("fake-java-home").toPath();
        Path fakeJava = fakeJavaHome.resolve("bin/java");
        Path javaMarker = isolatedRoot.resolve("fake-java-invoked");
        Files.createDirectories(fakeJava.getParent());
        Files.write(
                fakeJava,
                ("#!/bin/sh\n"
                        + "touch \"$FAKE_JAVA_MARKER\"\n"
                        + "printf '%s\\n' \"$@\"\n")
                        .getBytes(StandardCharsets.UTF_8));
        assertTrue(fakeJava.toFile().setExecutable(true));

        Path keyStore = isolatedRoot.resolve("tls/net-server.p12");
        if (keyStoreMode == KeyStoreMode.EXISTS) {
            Files.createDirectories(keyStore.getParent());
            Files.write(keyStore, new byte[]{0});
        }

        String rootExpression = "ROOT_DIR=\"$(cd \"$(dirname \"$0\")/..\" && pwd)\"";
        String isolatedRootExpression = "ROOT_DIR=\"" + isolatedRoot.toString().replace("\"", "\\\"") + "\"";
        String isolatedScript = sourceScript.replace(rootExpression, isolatedRootExpression);
        assertNotEquals(sourceScript, isolatedScript);

        Path script = temporaryFolder.newFile("run-net-server-zulu8.sh").toPath();
        Files.write(script, isolatedScript.getBytes(StandardCharsets.UTF_8));

        ProcessBuilder processBuilder = new ProcessBuilder("/bin/bash", script.toString());
        processBuilder.environment().put("JAVA_HOME", fakeJavaHome.toString());
        // [修改] 固定测试调试端口，避免继承开发机 DEBUG_PORT 导致断言受外部环境污染。
        processBuilder.environment().put("DEBUG_PORT", "18181");
        processBuilder.environment().put("JDWP", Boolean.toString(jdwp));
        processBuilder.environment().put("DRY_RUN", "false");
        processBuilder.environment().put("FAKE_JAVA_MARKER", javaMarker.toString());
        processBuilder.environment().remove("NET_SERVER_PUBLIC_IP");
        processBuilder.environment().remove("NET_SERVER_TLS_KEYSTORE");
        processBuilder.environment().remove("NET_SERVER_TLS_KEYSTORE_PASSWORD");
        processBuilder.environment().remove("NET_SERVER_TLS_KEYSTORE_AUTO_CREATE");
        if (publicIp != null) {
            processBuilder.environment().put("NET_SERVER_PUBLIC_IP", publicIp);
        }
        if (keyStoreMode != KeyStoreMode.MISSING_VARIABLE) {
            processBuilder.environment().put("NET_SERVER_TLS_KEYSTORE", keyStore.toString());
        }
        // [修改] 空密码是当前本地 PKCS12 的明确配置，不继承开发机环境。
        processBuilder.environment().put("NET_SERVER_TLS_KEYSTORE_PASSWORD", "");
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();
        String output = readAll(process.getInputStream());
        int exitCode = process.waitFor();
        Path runtimeJar = isolatedRoot.resolve(".runtime/net-server-1.0-SNAPSHOT-runtime.jar");
        return new ScriptResult(
                exitCode,
                output,
                runtimeJar.toString(),
                Files.exists(javaMarker));
    }

    private String readAll(InputStream inputStream) throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int length;
        while ((length = inputStream.read(buffer)) >= 0) {
            outputStream.write(buffer, 0, length);
        }
        return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
    }

    private enum KeyStoreMode {
        EXISTS,
        MISSING_VARIABLE,
        NONEXISTENT
    }

    private static final class ScriptResult {
        private final int exitCode;
        private final String output;
        private final String runtimeJar;
        private final boolean javaInvoked;

        private ScriptResult(
                int exitCode,
                String output,
                String runtimeJar,
                boolean javaInvoked) {
            this.exitCode = exitCode;
            this.output = output;
            this.runtimeJar = runtimeJar;
            this.javaInvoked = javaInvoked;
        }
    }
}
