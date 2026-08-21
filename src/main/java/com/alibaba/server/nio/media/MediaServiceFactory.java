package com.alibaba.server.nio.media;

import com.alibaba.server.common.BasicConstant;
import com.alibaba.server.nio.core.server.BasicServer;
import com.alibaba.server.nio.repository.file.service.FileService;
import com.alibaba.server.nio.repository.dynamic.mapper.UserDynamicRepository;
import com.alibaba.server.nio.service.file.security.TransferTokenFactory;
import com.alibaba.server.nio.service.file.security.TransferTokenService;
import com.alibaba.server.nio.service.file.security.TokenSecretResolver;
import com.alibaba.server.nio.service.file.StorageRootResolver;
import com.alibaba.server.nio.tls.TlsNetworkAddressResolver;
import org.apache.commons.lang.StringUtils;

import java.net.InetAddress;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

public final class MediaServiceFactory {
    private MediaServiceFactory() {
    }

    public static MediaTokenService tokenService() {
        Map<String, Object> config = BasicServer.getMap();
        return createTokenService(
                config,
                System::getenv,
                () -> TokenSecretResolver.loadOrCreateLocal("media-stream-token.secret"));
    }

    static MediaTokenService createTokenService(
            Map<String, Object> config,
            Function<String, String> environment,
            Supplier<String> localSecretSupplier) {
        String secret = TokenSecretResolver.resolve(
                config,
                environment,
                "MEDIA_STREAM_TOKEN_SECRET",
                BasicConstant.MEDIA_STREAM_TOKEN_SECRET,
                localSecretSupplier,
                "媒体令牌");
        return new MediaTokenService(
                secret,
                longConfig(config, BasicConstant.MEDIA_STREAM_TOKEN_EXPIRE_SECONDS, 300L));
    }

    /**
     * 获取文件传输令牌服务，媒体入口与文件传输复用同一登录后身份凭据。
     *
     * @return 文件传输令牌服务
     */
    public static TransferTokenService transferTokenService() {
        // [修改] 播放地址必须从已签名传输令牌派生用户，不能信任客户端 userName。
        return TransferTokenFactory.getInstance();
    }

    public static MediaAccessService accessService(MediaTokenService tokenService) {
        Map<String, Object> config = BasicServer.getMap();
        int port = intConfig(config, BasicConstant.NIO_MEDIA_STREAM_PORT, 10188);
        String publicHost = publicHost(config, System::getenv);
        FileService fileService = BasicServer.classPathXmlApplicationContext.getBean(FileService.class);
        UserDynamicRepository dynamicRepository = BasicServer.classPathXmlApplicationContext
                .getBean(UserDynamicRepository.class);
        return new MediaAccessService(
                fileService,
                new SafeFileResolver(StorageRootResolver.resolve(config)),
                tokenService,
                stringConfig(config, BasicConstant.MEDIA_STREAM_PUBLIC_SCHEME, "https"),
                publicHost,
                port,
                // [修改] 在线播放与下载使用同一动态可见性规则，好友可播放时间线中的视频。
                (userId, fileId) -> dynamicRepository.countVisibleMediaReferences(userId, fileId) > 0);
    }

    static String publicHost(
            Map<String, Object> config,
            Function<String, String> environment) {
        return publicHost(
                config,
                environment,
                value -> new TlsNetworkAddressResolver().resolve(value));
    }

    static String publicHost(
            Map<String, Object> config,
            Function<String, String> environment,
            Function<String, InetAddress> addressResolver) {
        String gatewayPublicIp = environment.apply("NET_SERVER_PUBLIC_IP");
        if (TlsNetworkAddressResolver.isAutomatic(gatewayPublicIp)) {
            gatewayPublicIp = System.getProperty("NET_SERVER_PUBLIC_IP");
        }
        if (!TlsNetworkAddressResolver.isAutomatic(gatewayPublicIp)) {
            // [修改] 播放 URL 与 TLS Gateway 共用公网地址，真机不会收到 localhost。
            return urlHost(gatewayPublicIp);
        }
        String mediaPublicHost = stringConfig(
                config,
                BasicConstant.MEDIA_STREAM_PUBLIC_HOST,
                null);
        if (!TlsNetworkAddressResolver.isAutomatic(mediaPublicHost)) {
            return urlHost(mediaPublicHost);
        }
        String configuredGatewayAddress = stringConfig(
                config,
                BasicConstant.TLS_GATEWAY_PUBLIC_IP,
                TlsNetworkAddressResolver.AUTO);
        InetAddress resolvedAddress = addressResolver.apply(configuredGatewayAddress);
        return urlHost(resolvedAddress.getHostAddress());
    }

    private static String urlHost(String value) {
        String host = value.trim();
        if (host.startsWith("[") && host.endsWith("]")) {
            return host;
        }
        // [修改] IPv6 URL authority 必须使用方括号，IPv4 和域名保持原样。
        return host.indexOf(':') >= 0 ? "[" + host + "]" : host;
    }

    public static int streamPort() {
        return intConfig(BasicServer.getMap(), BasicConstant.NIO_MEDIA_STREAM_PORT, 10188);
    }

    public static String streamBindIp() {
        return stringConfig(BasicServer.getMap(), BasicConstant.NIO_MEDIA_STREAM_BIND_IP, "127.0.0.1");
    }

    public static int streamBufferSize() {
        return intConfig(BasicServer.getMap(), BasicConstant.MEDIA_STREAM_BUFFER_SIZE, 256 * 1024);
    }

    public static int streamMaxThreads() {
        return intConfig(BasicServer.getMap(), BasicConstant.MEDIA_STREAM_MAX_THREADS, 64);
    }

    private static String stringConfig(Map<String, Object> config, String key, String defaultValue) {
        Object value = config.get(key);
        return value == null || StringUtils.isBlank(value.toString()) ? defaultValue : value.toString().trim();
    }

    private static int intConfig(Map<String, Object> config, String key, int defaultValue) {
        try {
            return Integer.parseInt(stringConfig(config, key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static long longConfig(Map<String, Object> config, String key, long defaultValue) {
        try {
            return Long.parseLong(stringConfig(config, key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
