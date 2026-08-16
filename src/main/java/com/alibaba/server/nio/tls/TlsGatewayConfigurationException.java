package com.alibaba.server.nio.tls;

/**
 * TLS Gateway 启动配置错误。
 */
public final class TlsGatewayConfigurationException extends RuntimeException {

    public TlsGatewayConfigurationException(String message) {
        super(message);
    }

    public TlsGatewayConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
