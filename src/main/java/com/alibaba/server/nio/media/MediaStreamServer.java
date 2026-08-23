package com.alibaba.server.nio.media;

import com.alibaba.server.common.BasicConstant;
import com.alibaba.server.nio.core.server.BasicServer;
import com.alibaba.server.nio.repository.file.service.FileService;
import com.alibaba.server.nio.service.file.security.TransferTokenService;
import com.sun.net.httpserver.HttpServer;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.Executors;

@Slf4j
public final class MediaStreamServer {
    private static volatile HttpServer server;

    private MediaStreamServer() {
    }

    public static synchronized void startup() throws IOException {
        if (server != null) {
            return;
        }

        int port = MediaServiceFactory.streamPort();
        String bindIp = MediaServiceFactory.streamBindIp();
        int bufferSize = MediaServiceFactory.streamBufferSize();
        int maxThreads = MediaServiceFactory.streamMaxThreads();
        MediaTokenService tokenService = MediaServiceFactory.tokenService();
        TransferTokenService transferTokenService = MediaServiceFactory.transferTokenService();
        MediaAccessService accessService = MediaServiceFactory.accessService(tokenService);

        // [修改] 明文媒体后端仅监听回环地址，由外层 HTTPS 终止后转发。
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(bindIp, port), 0);
        // [修改] 播放地址和跳转通知必须校验登录后签发的传输令牌。
        MediaStreamHandler handler = new MediaStreamHandler(
                accessService,
                tokenService,
                transferTokenService,
                bufferSize);
        httpServer.createContext("/media/play-url", handler);
        httpServer.createContext("/media/thumbnail", handler);
        httpServer.createContext("/media/stream", handler);
        httpServer.createContext("/media/seek", handler);
        httpServer.setExecutor(Executors.newFixedThreadPool(maxThreads));
        httpServer.start();
        server = httpServer;
        log.info("MediaStreamServer started on {}:{}", bindIp, port);
    }

    public static synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

}
