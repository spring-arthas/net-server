package com.alibaba.server;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class NetServerCommandLineOptionsTest {

    @Test
    public void acceptsPositionalServerIp() {
        assertEquals("192.168.1.20", NetServer.resolveCommandLinePublicIp(
                new String[]{"192.168.1.20"}));
    }

    @Test
    public void acceptsNamedServerIp() {
        assertEquals("192.168.1.20", NetServer.resolveCommandLinePublicIp(
                new String[]{"--server-ip=192.168.1.20"}));
    }

    @Test
    public void ignoresMissingOrBlankServerIp() {
        assertNull(NetServer.resolveCommandLinePublicIp(null));
        assertNull(NetServer.resolveCommandLinePublicIp(new String[0]));
        assertNull(NetServer.resolveCommandLinePublicIp(new String[]{"--server-ip="}));
    }
}
