package com.alibaba.server.nio.tls;

import java.util.Objects;

/**
 * TLS 公网端口到本机明文后端的映射。
 */
public final class TlsGatewayEndpoint {
    private final String name;
    private final int publicPort;
    private final String backendHost;
    private final int backendPort;

    public TlsGatewayEndpoint(String name, int publicPort, String backendHost, int backendPort) {
        this.name = Objects.requireNonNull(name, "name");
        this.publicPort = publicPort;
        this.backendHost = Objects.requireNonNull(backendHost, "backendHost");
        this.backendPort = backendPort;
    }

    public String getName() {
        return name;
    }

    public int getPublicPort() {
        return publicPort;
    }

    public String getBackendHost() {
        return backendHost;
    }

    public int getBackendPort() {
        return backendPort;
    }

    @Override
    public String toString() {
        return "TlsGatewayEndpoint{" +
                "name='" + name + '\'' +
                ", publicPort=" + publicPort +
                ", backendHost='" + backendHost + '\'' +
                ", backendPort=" + backendPort +
                '}';
    }
}
