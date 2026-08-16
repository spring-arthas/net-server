package com.alibaba.server.nio.service.file.security;

import com.alibaba.server.common.BasicConstant;
import com.alibaba.server.nio.core.server.BasicServer;

import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

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
                instance = create(BasicServer.getMap(), System::getenv);
            }
            return instance;
        }
    }

    static SessionTokenService create(Map<String, Object> config, Function<String, String> environment) {
        return create(config, environment, LocalSessionSecretStore::loadOrCreateDefault);
    }

    static SessionTokenService create(
            Map<String, Object> config,
            Function<String, String> environment,
            Supplier<String> localSecretSupplier) {
        String environmentSecret = environment == null ? null : environment.apply("USER_SESSION_TOKEN_SECRET");
        String configuredSecret = value(config, BasicConstant.USER_SESSION_TOKEN_SECRET, null);
        String secret = isBlank(environmentSecret) ? configuredSecret : environmentSecret;
        if (isBlank(secret) && localSecretSupplier != null) {
            secret = localSecretSupplier.get();
        }
        long expires = longValue(config, BasicConstant.USER_SESSION_TOKEN_EXPIRE_SECONDS, 604800L);
        try {
            return new SessionTokenService(secret, expires);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Configure a non-default session-token secret or allow the local secret file to be created",
                    e);
        }
    }

    private static String value(Map<String, Object> config, String key, String fallback) {
        if (config == null || config.get(key) == null) {
            return fallback;
        }
        String value = String.valueOf(config.get(key)).trim();
        return value.isEmpty() ? fallback : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static long longValue(Map<String, Object> config, String key, long fallback) {
        try {
            return Long.parseLong(value(config, key, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
