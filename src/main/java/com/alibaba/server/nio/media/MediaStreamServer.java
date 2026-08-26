package com.alibaba.server.nio.media;

import com.alibaba.server.common.BasicConstant;
import com.alibaba.server.nio.core.server.BasicServer;
import com.alibaba.server.nio.repository.file.service.FileService;
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
        int bufferSize = MediaServiceFactory.streamBufferSize();
        int maxThreads = MediaServiceFactory.streamMaxThreads();
        MediaTokenService tokenService = MediaServiceFactory.tokenService();
        MediaAccessService accessService = MediaServiceFactory.accessService(tokenService);

        HttpServer httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        MediaStreamHandler handler = new MediaStreamHandler(accessService, tokenService, bufferSize);
        httpServer.createContext("/media/play-url", handler);
        httpServer.createContext("/media/stream", handler);
        httpServer.setExecutor(Executors.newFixedThreadPool(maxThreads));
        httpServer.start();
        server = httpServer;
        log.info("MediaStreamServer started on port {}", port);
    }

    public static synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

}
