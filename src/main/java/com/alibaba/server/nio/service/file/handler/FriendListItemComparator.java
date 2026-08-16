package com.alibaba.server.nio.service.file.handler;

import com.alibaba.fastjson.JSONObject;

import java.util.Comparator;

/** 好友列表的服务端权威排序规则。 */
final class FriendListItemComparator implements Comparator<JSONObject> {

    static final FriendListItemComparator INSTANCE = new FriendListItemComparator();

    private FriendListItemComparator() {
    }

    @Override
    public int compare(JSONObject left, JSONObject right) {
        int compared = Boolean.compare(isPinned(right), isPinned(left));
        if (compared != 0) {
            return compared;
        }
        if (isPinned(left)) {
            compared = Long.compare(pinTime(right), pinTime(left));
            if (compared != 0) {
                return compared;
            }
        }
        compared = Boolean.compare(hasUnread(right), hasUnread(left));
        if (compared != 0) {
            return compared;
        }
        compared = Boolean.compare(isOnline(right), isOnline(left));
        if (compared != 0) {
            return compared;
        }
        compared = displayName(left).compareToIgnoreCase(displayName(right));
        if (compared != 0) {
            return compared;
        }
        return Integer.compare(friendId(left), friendId(right));
    }

    private boolean isPinned(JSONObject value) {
        return Boolean.TRUE.equals(value.getBoolean("pinned"));
    }

    private long pinTime(JSONObject value) {
        Long pinnedAt = value.getLong("pinnedAt");
        return pinnedAt == null ? Long.MIN_VALUE : pinnedAt;
    }

    private boolean hasUnread(JSONObject value) {
        Integer unreadCount = value.getInteger("unreadCount");
        return unreadCount != null && unreadCount > 0;
    }

    private boolean isOnline(JSONObject value) {
        return Boolean.TRUE.equals(value.getBoolean("online"));
    }

    private String displayName(JSONObject value) {
        String alias = value.getString("alias");
        if (alias != null && !alias.trim().isEmpty()) {
            return alias;
        }
        String nickname = value.getString("nickName");
        if (nickname != null && !nickname.trim().isEmpty()) {
            return nickname;
        }
        String username = value.getString("userName");
        return username == null ? "" : username;
    }

    private int friendId(JSONObject value) {
        Integer friendId = value.getInteger("friendId");
        return friendId == null ? Integer.MAX_VALUE : friendId;
    }
}
