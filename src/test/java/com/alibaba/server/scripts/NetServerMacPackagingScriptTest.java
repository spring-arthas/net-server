package com.alibaba.server.scripts;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class NetServerMacPackagingScriptTest {

    @Test
    public void packagesNativeMacApplicationWithBundledRuntime() throws Exception {
        Path repositoryRoot = new File(".").toPath().toAbsolutePath().normalize();
        Path script = repositoryRoot.resolve("scripts/package-net-server-macos.sh");

        assertTrue("缺少 macOS DMG 打包脚本: " + script, Files.isRegularFile(script));

        String source = new String(Files.readAllBytes(script), StandardCharsets.UTF_8);
        assertTrue(source.contains("PACKAGING_JAVA_HOME"));
        assertTrue(source.contains("MAVEN_JAVA_HOME"));
        assertTrue(source.contains("--type dmg"));
        assertTrue(source.contains("--input"));
        assertTrue(source.contains("--main-jar"));
        assertTrue(source.contains("--main-class com.alibaba.server.NetServer"));
        assertTrue(source.contains("PACKAGE_IDENTIFIER:-com.alibaba.netserver"));
        assertTrue(source.contains("--mac-package-identifier"));
        assertTrue(source.contains("JPACKAGE_OUTPUT"));
        assertTrue(source.contains("mv \"$JPACKAGE_OUTPUT\" \"$PACKAGE_PATH\""));
        assertTrue(source.contains("MAC_SIGN"));
        assertTrue(source.contains("--mac-sign"));
    }
}
