package com.alibaba.server.nio.core.server;

import com.alibaba.server.common.BasicConstant;
import com.alibaba.server.nio.tls.TlsGatewayConfig;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class NioServerContextTlsConfigurationTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void sharesResolvedTlsAddressWithMediaUrlConfiguration() {
        Map<String, Object> values = configuration();
        String keyStorePath = temporaryFolder.getRoot().toPath()
                .resolve("tls/net-server.p12")
                .toString();
        TlsGatewayConfig tlsConfig = TlsGatewayConfig.load(
                values,
                name -> {
                    if ("NET_SERVER_PUBLIC_IP".equals(name)) {
                        return "192.168.0.101";
                    }
                    if ("NET_SERVER_TLS_KEYSTORE".equals(name)) {
                        return keyStorePath;
                    }
                    return null;
                });

        NioServerContext.applyResolvedTlsConfiguration(values, tlsConfig);

        assertEquals("192.168.0.101", values.get(BasicConstant.TLS_GATEWAY_PUBLIC_IP));
        assertEquals("192.168.0.101", values.get(BasicConstant.MEDIA_STREAM_PUBLIC_HOST));
    }

    private Map<String, Object> configuration() {
        Map<String, Object> values = new HashMap<>();
        values.put(BasicConstant.NIO_BIND_IP, "127.0.0.1");
        values.put(BasicConstant.NIO_MEDIA_STREAM_BIND_IP, "127.0.0.1");
        values.put(BasicConstant.NIO_TEXT_PORT, "10086");
        values.put(BasicConstant.NIO_FILE_UPLOAD_PORT, "10087");
        values.put(BasicConstant.NIO_FILE_DOWNLOAD_PORT, "10088");
        values.put(BasicConstant.NIO_MEDIA_STREAM_PORT, "10188");
        values.put(BasicConstant.TLS_GATEWAY_ENABLED, "true");
        values.put(BasicConstant.TLS_GATEWAY_PUBLIC_IP, "auto");
        values.put(BasicConstant.MEDIA_STREAM_PUBLIC_HOST, "auto");
        return values;
    }
}
