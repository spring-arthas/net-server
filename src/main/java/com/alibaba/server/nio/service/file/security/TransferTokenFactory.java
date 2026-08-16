package com.alibaba.server.nio.service.file.security;

import com.alibaba.server.common.BasicConstant;
import com.alibaba.server.nio.core.server.BasicServer;

import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

public final class TransferTokenFactory {
    private static volatile TransferTokenService instance;

    private TransferTokenFactory() {
    }

    public static TransferTokenService getInstance() {
        TransferTokenService local = instance;
        if (local != null) {
            return local;
        }
        synchronized (TransferTokenFactory.class) {
            if (instance == null) {
                Map<String, Object> config = BasicServer.getMap();
                instance = create(
                        config,
                        System::getenv,
                        () -> TokenSecretResolver.loadOrCreateLocal("file-transfer-token.secret"));
            }
            return instance;
        }
    }

    static TransferTokenService create(
            Map<String, Object> config,
            Function<String, String> environment,
            Supplier<String> localSecretSupplier) {
        String secret = TokenSecretResolver.resolve(
                config,
                environment,
                "FILE_TRANSFER_TOKEN_SECRET",
                BasicConstant.FILE_TRANSFER_TOKEN_SECRET,
                localSecretSupplier,
                "文件传输令牌");
        long expires = longValue(config, BasicConstant.FILE_TRANSFER_TOKEN_EXPIRE_SECONDS, 86400L);
        return new TransferTokenService(secret, expires);
    }

    private static String value(Map<String, Object> config, String key, String fallback) {
        if (config == null || config.get(key) == null) {
            return fallback;
        }
        String value = String.valueOf(config.get(key)).trim();
        return value.isEmpty() ? fallback : value;
    }

    private static long longValue(Map<String, Object> config, String key, long fallback) {
        try {
            return Long.parseLong(value(config, key, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
