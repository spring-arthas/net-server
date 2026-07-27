package com.alibaba.server.nio.service.file.security;

import com.alibaba.server.common.BasicConstant;
import com.alibaba.server.nio.core.server.BasicServer;

import java.util.Map;

public final class SessionTokenFactory {
    private static volatile SessionTokenService instance;

    private SessionTokenFactory() {
    }

    public static SessionTokenService getInstance() {
        SessionTokenService local = instance;
        if (local != null) {
            return local;
        }
        synchronized (SessionTokenFactory.class) {
            if (instance == null) {
                Map<String, Object> config = BasicServer.getMap();
                String transferSecret = value(config, BasicConstant.FILE_TRANSFER_TOKEN_SECRET, "change-me-session-secret");
                String secret = value(config, BasicConstant.USER_SESSION_TOKEN_SECRET, transferSecret);
                long expires = longValue(config, BasicConstant.USER_SESSION_TOKEN_EXPIRE_SECONDS, 604800L);
                instance = new SessionTokenService(secret, expires);
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
