package com.alibaba.server.nio.repository.chat.service;

import com.alibaba.server.nio.repository.chat.mapper.UserFriendMessageDO;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 聊天消息搜索分页结果。 */
public final class ChatMessageSearchPage {
    private final List<UserFriendMessageDO> messages;
    private final boolean hasMore;

    public ChatMessageSearchPage(List<UserFriendMessageDO> messages, boolean hasMore) {
        List<UserFriendMessageDO> safeMessages = messages == null
                ? Collections.<UserFriendMessageDO>emptyList() : messages;
        this.messages = Collections.unmodifiableList(new ArrayList<UserFriendMessageDO>(safeMessages));
        this.hasMore = hasMore;
    }

    public List<UserFriendMessageDO> getMessages() {
        return messages;
    }

    public boolean isHasMore() {
        return hasMore;
    }
}
