package com.alibaba.server.nio.service.file.handler;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.server.nio.model.file.FileUploadFrame.FrameType;
import com.alibaba.server.nio.repository.chat.mapper.UserFriendMessageDO;
import com.alibaba.server.nio.repository.chat.service.ChatHistoryPage;
import java.util.Arrays;
import java.util.Date;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TextTransmissionHandlerHistoryContractTest {

    @Test
    public void historyResponseContainsCursorMetadataWithoutAvatars() {
        UserFriendMessageDO first = message(8L, 1, 2, "older", 1_700_000_000_000L);
        UserFriendMessageDO second = message(9L, 2, 1, "newer", 1_700_000_060_000L);
        ChatHistoryPage page = new ChatHistoryPage(Arrays.asList(first, second), true, 8L, 9L);

        JSONObject data = ChatHistoryResponseBuilder.buildResponseData(page);

        assertTrue(data.containsKey("list"));
        assertEquals(true, data.getBooleanValue("hasMore"));
        assertEquals(Long.valueOf(8L), data.getLong("nextBeforeMessageId"));
        assertEquals(Long.valueOf(9L), data.getLong("latestMessageId"));
        assertFalse(data.containsKey("avatars"));
        assertEquals(2, data.getJSONArray("list").size());
    }

    @Test
    public void historyItemIncludesServerTimestampAndPersistedFields() {
        UserFriendMessageDO message = message(8L, 1, 2, "hello", 1_700_000_000_000L);

        JSONObject item = ChatHistoryResponseBuilder.buildItem(message, Long.MAX_VALUE, 0L);

        assertEquals(Long.valueOf(8L), item.getLong("id"));
        assertEquals(1, item.getIntValue("senderId"));
        assertEquals(2, item.getIntValue("receiverId"));
        assertEquals("hello", item.getString("content"));
        assertEquals(1_700_000_000_000L, item.getLongValue("gmtCreated"));
        assertTrue(item.containsKey("groupTime"));
        assertTrue(item.containsKey("msgTimeStr"));
    }

    @Test
    public void historyFramesDoNotLogCompletePayload() {
        assertFalse(ChatHistoryResponseBuilder.shouldLogPayload(FrameType.CHAT_MSG_HISTORY_RESPONSE));
        assertTrue(ChatHistoryResponseBuilder.shouldLogPayload(FrameType.CHAT_MSG_RESPONSE));
    }

    @Test
    public void responseListOrderMatchesPageOrder() {
        ChatHistoryPage page = new ChatHistoryPage(Arrays.asList(
                message(5L, 1, 2, "five", 1_700_000_000_000L),
                message(6L, 2, 1, "six", 1_700_000_060_000L)), false, 5L, 6L);

        JSONArray list = ChatHistoryResponseBuilder.buildResponseData(page).getJSONArray("list");

        assertEquals(5L, list.getJSONObject(0).getLongValue("id"));
        assertEquals(6L, list.getJSONObject(1).getLongValue("id"));
    }

    @Test
    public void realtimePushCopiesOptionalQuoteFieldsFromSendRequest() {
        UserFriendMessageDO saved = message(18L, 1, 2, "reply", 1_700_000_120_000L);
        JSONObject request = new JSONObject();
        request.put("quoteMsgId", 9L);
        request.put("quoteMsgContent", "上一条消息");
        request.put("quoteMsgSenderName", "张三");

        JSONObject push = ChatMessagePushBuilder.build(
                saved, 1L, "reply", "avatar-base64", request);

        assertEquals(Long.valueOf(9L), push.getLong("quoteMsgId"));
        assertEquals("上一条消息", push.getString("quoteMsgContent"));
        assertEquals("张三", push.getString("quoteMsgSenderName"));
    }

    @Test
    public void realtimePushKeepsLegacyShapeWhenQuoteFieldsAreAbsent() {
        UserFriendMessageDO saved = message(19L, 1, 2, "plain", 1_700_000_180_000L);

        JSONObject push = ChatMessagePushBuilder.build(
                saved, 1L, "plain", "", new JSONObject());

        assertFalse(push.containsKey("quoteMsgId"));
        assertFalse(push.containsKey("quoteMsgContent"));
        assertFalse(push.containsKey("quoteMsgSenderName"));
    }

    @Test
    public void realtimePushLimitsQuotePreviewToAndroidCompatibleLength() {
        UserFriendMessageDO saved = message(20L, 1, 2, "reply", 1_700_000_240_000L);
        JSONObject request = new JSONObject();
        char[] oversized = new char[240];
        Arrays.fill(oversized, 'a');
        request.put("quoteMsgContent", new String(oversized));

        JSONObject push = ChatMessagePushBuilder.build(
                saved, 1L, "reply", "", request);

        assertEquals(200, push.getString("quoteMsgContent").length());
    }

    private static UserFriendMessageDO message(long id, int senderId, int receiverId,
            String content, long createdAt) {
        UserFriendMessageDO message = new UserFriendMessageDO();
        message.setId(id);
        message.setSenderId(senderId);
        message.setReceiverId(receiverId);
        message.setContent(content);
        message.setMsgType("TEXT");
        message.setStatus(1);
        message.setGmtCreated(new Date(createdAt));
        return message;
    }
}
