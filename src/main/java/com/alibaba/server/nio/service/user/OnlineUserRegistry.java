package com.alibaba.server.nio.service.user;

import com.alibaba.server.nio.core.server.BasicServer;
import com.alibaba.server.nio.model.SocketChannelContext;
import org.apache.commons.lang.StringUtils;

import java.nio.channels.SocketChannel;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 在线用户状态工具。
 *
 * 当前在线状态只代表用户文本通道已登录且 SocketChannel 仍处于连接状态。
 */
public final class OnlineUserRegistry {

    private static final String ATTR_LOGGED_IN_USER_ID = "loggedInUserId";

    private OnlineUserRegistry() {
    }

    public static boolean isUserOnline(Integer userId) {
        return userId != null && isUserOnline(userId.longValue());
    }

    public static boolean isUserOnline(Long userId) {
        return isUserOnline(userId, BasicServer.onlineUserChannels);
    }

    /**
     * 将用户绑定到当前文本连接。
     *
     * 同一条连接重新登录其他账号前，必须先移除它原有的所有用户映射，
     * 避免多个 userId 同时指向同一个 SocketChannelContext。
     *
     * @return 被清理的旧用户映射数量
     */
    public static int bindUser(Long userId, SocketChannelContext context) {
        if (userId == null || context == null) {
            return 0;
        }
        int removed = cleanupContext(context, BasicServer.onlineUserChannels);
        BasicServer.onlineUserChannels.put(userId, context);
        return removed;
    }

    /**
     * 仅当在线表中的映射仍属于当前连接时才解除绑定。
     * 防止旧连接退出时误删同账号已经建立的新连接。
     */
    public static boolean unbindUser(Long userId, SocketChannelContext context) {
        return userId != null
                && context != null
                && BasicServer.onlineUserChannels.remove(userId, context);
    }

    /**
     * 获取身份与连接状态均有效的在线用户连接。
     * 对身份不匹配或已经关闭的残留映射进行即时清理。
     */
    public static SocketChannelContext getActiveUserContext(Long userId) {
        if (userId == null) {
            return null;
        }
        SocketChannelContext context = BasicServer.onlineUserChannels.get(userId);
        if (isActiveUserContext(userId, context)) {
            return context;
        }
        if (context != null) {
            BasicServer.onlineUserChannels.remove(userId, context);
        }
        return null;
    }

    static boolean isUserOnline(Long userId, Map<Long, SocketChannelContext> channels) {
        if (userId == null || channels == null) {
            return false;
        }
        SocketChannelContext context = channels.get(userId);
        return isActiveUserContext(userId, context);
    }

    public static int cleanupConnection(String remoteAddress) {
        return cleanupConnection(remoteAddress, BasicServer.onlineUserChannels);
    }

    public static int cleanupSocketChannel(SocketChannel socketChannel) {
        return cleanupSocketChannel(socketChannel, BasicServer.onlineUserChannels);
    }

    static int cleanupConnection(String remoteAddress, ConcurrentHashMap<Long, SocketChannelContext> channels) {
        if (StringUtils.isBlank(remoteAddress) || channels == null || channels.isEmpty()) {
            return 0;
        }
        int removed = 0;
        for (Map.Entry<Long, SocketChannelContext> entry : channels.entrySet()) {
            SocketChannelContext context = entry.getValue();
            if (context != null && remoteAddress.equals(context.getRemoteAddress())) {
                if (channels.remove(entry.getKey(), context)) {
                    removed++;
                }
            }
        }
        return removed;
    }

    static int cleanupSocketChannel(SocketChannel socketChannel, ConcurrentHashMap<Long, SocketChannelContext> channels) {
        if (socketChannel == null || channels == null || channels.isEmpty()) {
            return 0;
        }
        int removed = 0;
        for (Map.Entry<Long, SocketChannelContext> entry : channels.entrySet()) {
            SocketChannelContext context = entry.getValue();
            if (context != null && socketChannel == context.getSocketChannel()) {
                if (channels.remove(entry.getKey(), context)) {
                    removed++;
                }
            }
        }
        return removed;
    }

    private static int cleanupContext(SocketChannelContext targetContext,
            ConcurrentHashMap<Long, SocketChannelContext> channels) {
        if (targetContext == null || channels == null || channels.isEmpty()) {
            return 0;
        }
        int removed = 0;
        for (Map.Entry<Long, SocketChannelContext> entry : channels.entrySet()) {
            if (entry.getValue() == targetContext && channels.remove(entry.getKey(), targetContext)) {
                removed++;
            }
        }
        return removed;
    }

    private static boolean isActiveUserContext(Long userId, SocketChannelContext context) {
        if (context == null) {
            return false;
        }
        Object loggedInUserId = context.getAttribute(ATTR_LOGGED_IN_USER_ID);
        if (!matchesUserId(userId, loggedInUserId)) {
            return false;
        }
        SocketChannel socketChannel = context.getSocketChannel();
        return socketChannel != null && socketChannel.isOpen() && socketChannel.isConnected();
    }

    private static boolean matchesUserId(Long expectedUserId, Object actualUserId) {
        if (expectedUserId == null || actualUserId == null) {
            return false;
        }
        if (actualUserId instanceof Number) {
            return expectedUserId.longValue() == ((Number) actualUserId).longValue();
        }
        if (actualUserId instanceof String) {
            try {
                return expectedUserId.longValue() == Long.parseLong((String) actualUserId);
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return false;
    }
}
