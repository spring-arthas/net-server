package com.alibaba.server.nio.tls;

import org.junit.Test;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

// [修改] 后端按真实端口状态就绪，不使用固定 sleep 猜测启动时间。
public class TlsBackendReadinessProbeTest {

    @Test
    public void returnsAfterAllBackendsAcceptConnections() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        try (ServerSocket first = new ServerSocket(0, 10, loopback);
                ServerSocket second = new ServerSocket(0, 10, loopback)) {
            List<TlsGatewayEndpoint> endpoints = Arrays.asList(
                    new TlsGatewayEndpoint("first", 0, "127.0.0.1", first.getLocalPort()),
                    new TlsGatewayEndpoint("second", 0, "127.0.0.1", second.getLocalPort()));

            TlsBackendReadinessProbe.await(endpoints, 1_000);
        }
    }

    @Test
    public void failsWithEndpointContextAfterTimeout() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        TlsGatewayEndpoint endpoint = new TlsGatewayEndpoint(
                "control",
                0,
                "127.0.0.1",
                closedPort);

        try {
            TlsBackendReadinessProbe.await(Collections.singletonList(endpoint), 150);
            fail("expected TlsGatewayStartupException");
        } catch (TlsGatewayStartupException exception) {
            assertTrue(exception.getMessage(), exception.getMessage().contains("control"));
            assertTrue(exception.getMessage(), exception.getMessage().contains(String.valueOf(closedPort)));
        }
    }

    @Test
    public void rejectsNonPositiveTimeout() {
        try {
            TlsBackendReadinessProbe.await(Collections.emptyList(), 0);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException exception) {
            assertTrue(exception.getMessage().contains("timeoutMillis"));
        }
    }
}
