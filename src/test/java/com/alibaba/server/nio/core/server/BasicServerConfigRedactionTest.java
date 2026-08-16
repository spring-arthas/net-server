package com.alibaba.server.nio.core.server;

import com.alibaba.server.common.BasicConstant;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class BasicServerConfigRedactionTest {

    @Test
    public void secretsAreRedactedWhenConfigurationIsPrinted() {
        assertEquals("[REDACTED]", BasicServer.safeConfigValue(
                BasicConstant.USER_SESSION_TOKEN_SECRET,
                "deployment-secret"));
        assertEquals("10086", BasicServer.safeConfigValue("NIO.SERVER.PORT", "10086"));
    }
}
