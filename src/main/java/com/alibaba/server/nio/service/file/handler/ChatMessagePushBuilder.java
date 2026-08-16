package com.alibaba.server.nio.service.file.handler;

import com.alibaba.fastjson.JSONObject;
import com.alibaba.server.nio.repository.chat.mapper.UserFriendMessageDO;

final class ChatMessagePushBuilder {

    private static final int MAX_QUOTE_CONTENT_LENGTH = 200;
    private static final int MAX_QUOTE_SENDER_NAME_LENGTH = 64;

    private ChatMessagePushBuilder() {
    }

    static JSONObject build(UserFriendMessageDO savedMsg, Long senderId,
            String content, String senderAvatar, JSONObject request) {
        JSONObject pushData = new JSONObject();
        pushData.put("messageId", savedMsg.getId());
        pushData.put("senderId", senderId);
        pushData.put("content", content);
        pushData.put("msgType", savedMsg.getMsgType());
        pushData.put("avatar", senderAvatar);
        pushData.put("gmtCreated",
                savedMsg.getGmtCreated() != null ? savedMsg.getGmtCreated().getTime() : System.currentTimeMillis());

        // [修改] quote 字段仍是 0x50/0x51 JSON 的可选扩展，旧 Android/macOS 客户端会直接忽略。
        Long quoteMsgId = request.getLong("quoteMsgId");
        String quoteMsgContent = limitOptionalText(request.getString("quoteMsgContent"),
                MAX_QUOTE_CONTENT_LENGTH);
        String quoteMsgSenderName = limitOptionalText(request.getString("quoteMsgSenderName"),
                MAX_QUOTE_SENDER_NAME_LENGTH);
        if (quoteMsgId != null && quoteMsgId > 0) {
            pushData.put("quoteMsgId", quoteMsgId);
        }
        if (quoteMsgContent != null) {
            pushData.put("quoteMsgContent", quoteMsgContent);
        }
        if (quoteMsgSenderName != null) {
            pushData.put("quoteMsgSenderName", quoteMsgSenderName);
        }
        return pushData;
    }

    private static String limitOptionalText(String value, int maximumLength) {
        if (org.apache.commons.lang.StringUtils.isBlank(value)) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() <= maximumLength
                ? normalized : normalized.substring(0, maximumLength);
    }
}
