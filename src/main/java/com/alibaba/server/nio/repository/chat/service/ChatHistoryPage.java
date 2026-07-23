package com.alibaba.server.nio.repository.chat.service;

import com.alibaba.server.nio.repository.chat.mapper.UserFriendMessageDO;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ChatHistoryPage {

    private final List<UserFriendMessageDO> messages;
    private final boolean hasMore;
    private final Long nextBeforeMessageId;
    private final Long latestMessageId;

    public ChatHistoryPage(List<UserFriendMessageDO> messages, boolean hasMore,
            Long nextBeforeMessageId, Long latestMessageId) {
        this.messages = Collections.unmodifiableList(new ArrayList<>(messages));
        this.hasMore = hasMore;
        this.nextBeforeMessageId = nextBeforeMessageId;
        this.latestMessageId = latestMessageId;
    }

    public List<UserFriendMessageDO> getMessages() {
        return messages;
    }

    public boolean isHasMore() {
        return hasMore;
    }

    public Long getNextBeforeMessageId() {
        return nextBeforeMessageId;
    }

    public Long getLatestMessageId() {
        return latestMessageId;
    }
}
