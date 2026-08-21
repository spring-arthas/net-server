package com.alibaba.server.nio.media;

import com.alibaba.server.common.BasicConstant;
import org.junit.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

// [修改] 媒体播放 URL 必须和 TLS Gateway 使用同一个公网地址，不能把 localhost 发给真机。
public class MediaServiceFactoryEndpointTest {

    @Test
    public void publicIpEnvironmentOverridesLocalhostConfiguration() {
        Map<String, Object> config = new HashMap<>();
        config.put(BasicConstant.MEDIA_STREAM_PUBLIC_HOST, "localhost");

        String publicHost = MediaServiceFactory.publicHost(
                config,
                name -> "NET_SERVER_PUBLIC_IP".equals(name) ? "172.21.32.64" : null);

        assertEquals("172.21.32.64", publicHost);
    }

    @Test
    public void commandLineSystemPropertyOverridesStaticMediaHost() {
        Map<String, Object> config = new HashMap<>();
        config.put(BasicConstant.MEDIA_STREAM_PUBLIC_HOST, "172.21.32.131");
        String previous = System.getProperty("NET_SERVER_PUBLIC_IP");
        try {
            System.setProperty("NET_SERVER_PUBLIC_IP", "192.168.1.20");
            assertEquals("192.168.1.20", MediaServiceFactory.publicHost(config, name -> null));
        } finally {
            if (previous == null) {
                System.clearProperty("NET_SERVER_PUBLIC_IP");
            } else {
                System.setProperty("NET_SERVER_PUBLIC_IP", previous);
            }
        }
    }

    @Test
    public void configuredHostRemainsFallbackWhenGatewayEnvironmentIsMissing() {
        Map<String, Object> config = new HashMap<>();
        config.put(BasicConstant.MEDIA_STREAM_PUBLIC_HOST, "media.example.com");

        String publicHost = MediaServiceFactory.publicHost(config, name -> null);

        assertEquals("media.example.com", publicHost);
    }

    @Test
    public void resolvesAutomaticMediaHostFromCurrentTlsAddress() throws UnknownHostException {
        Map<String, Object> config = new HashMap<>();
        config.put(BasicConstant.MEDIA_STREAM_PUBLIC_HOST, "auto");
        config.put(BasicConstant.TLS_GATEWAY_PUBLIC_IP, "auto");
        InetAddress resolvedAddress = InetAddress.getByName("192.168.0.101");

        String publicHost = MediaServiceFactory.publicHost(
                config,
                name -> "NET_SERVER_PUBLIC_IP".equals(name) ? "auto" : null,
                value -> resolvedAddress);

        assertEquals("192.168.0.101", publicHost);
    }

    @Test
    public void wrapsIpv6PublicAddressForMediaUrlAuthority() {
        Map<String, Object> config = new HashMap<>();

        String publicHost = MediaServiceFactory.publicHost(
                config,
                name -> "NET_SERVER_PUBLIC_IP".equals(name) ? "2001:db8::1" : null);

        // [修改] IPv6 放进 URL authority 时必须加方括号，否则端口分隔符无法解析。
        assertEquals("[2001:db8::1]", publicHost);
    }
}
