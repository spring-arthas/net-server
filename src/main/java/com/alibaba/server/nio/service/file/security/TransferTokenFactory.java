package com.alibaba.server.nio.service.file.security;

import com.alibaba.server.common.BasicConstant;
import com.alibaba.server.nio.core.server.BasicServer;

import java.util.Map;

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
                String secret = value(config, BasicConstant.FILE_TRANSFER_TOKEN_SECRET,
                        value(config, BasicConstant.MEDIA_STREAM_TOKEN_SECRET, "change-me"));
                long expires = longValue(config, BasicConstant.FILE_TRANSFER_TOKEN_EXPIRE_SECONDS, 86400L);
                instance = new TransferTokenService(secret, expires);
            }
            return instance;
        }
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
