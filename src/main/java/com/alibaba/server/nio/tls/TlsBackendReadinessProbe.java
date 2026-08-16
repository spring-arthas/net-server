package com.alibaba.server.nio.tls;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 等待明文回环后端真正开始监听。
 */
public final class TlsBackendReadinessProbe {
    private static final int ATTEMPT_TIMEOUT_MILLIS = 100;
    private static final int POLL_INTERVAL_MILLIS = 50;

    private TlsBackendReadinessProbe() {
    }

    /**
     * 等待所有 TLS 端点对应的明文后端可连接。
     *
     * @param endpoints 端点列表
     * @param timeoutMillis 总等待时间
     */
    public static void await(List<TlsGatewayEndpoint> endpoints, int timeoutMillis) {
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("timeoutMillis 必须大于 0");
        }
        List<TlsGatewayEndpoint> pending = new ArrayList<>(endpoints);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (!pending.isEmpty()) {
            pending.removeIf(TlsBackendReadinessProbe::isReady);
            if (pending.isEmpty()) {
                return;
            }
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                throw notReady(pending);
            }
            long waitNanos = Math.min(
                    remainingNanos,
                    TimeUnit.MILLISECONDS.toNanos(POLL_INTERVAL_MILLIS));
            try {
                // [修改] 只在端口未就绪时短暂轮询，端口全部可用后立即继续启动。
                TimeUnit.NANOSECONDS.sleep(waitNanos);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new TlsGatewayStartupException("等待 TLS 后端就绪时线程被中断", exception);
            }
        }
    }

    private static boolean isReady(TlsGatewayEndpoint endpoint) {
        try (Socket socket = new Socket()) {
            socket.connect(
                    new InetSocketAddress(endpoint.getBackendHost(), endpoint.getBackendPort()),
                    ATTEMPT_TIMEOUT_MILLIS);
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    private static TlsGatewayStartupException notReady(List<TlsGatewayEndpoint> pending) {
        String endpoints = pending.stream()
                .map(value -> value.getName() + "="
                        + value.getBackendHost() + ":" + value.getBackendPort())
                .collect(Collectors.joining(", "));
        return new TlsGatewayStartupException("TLS 明文后端未就绪: " + endpoints);
    }
}
