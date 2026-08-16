package com.alibaba.server.nio.tls;

/**
 * TLS Gateway 证书、监听或后端就绪失败。
 */
public final class TlsGatewayStartupException extends RuntimeException {

    public TlsGatewayStartupException(String message) {
        super(message);
    }

    public TlsGatewayStartupException(String message, Throwable cause) {
        super(message, cause);
    }
}
