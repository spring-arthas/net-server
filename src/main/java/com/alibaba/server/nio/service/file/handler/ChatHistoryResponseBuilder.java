package com.alibaba.server.nio.service.file.handler;

import com.alibaba.fastjson.JSONObject;
import com.alibaba.server.nio.model.file.FileUploadFrame.FrameType;
import com.alibaba.server.nio.repository.chat.mapper.UserFriendMessageDO;
import com.alibaba.server.nio.repository.chat.service.ChatHistoryPage;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

final class ChatHistoryResponseBuilder {

    private ChatHistoryResponseBuilder() {
    }

    static JSONObject buildResponseData(ChatHistoryPage historyPage) {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        long todayStart = calendar.getTimeInMillis();
        calendar.add(Calendar.DATE, -1);
        long yesterdayStart = calendar.getTimeInMillis();

        List<JSONObject> resultList = new ArrayList<>();
        for (UserFriendMessageDO message : historyPage.getMessages()) {
            resultList.add(buildItem(message, todayStart, yesterdayStart));
        }

        JSONObject responseData = new JSONObject();
        responseData.put("list", resultList);
        responseData.put("hasMore", historyPage.isHasMore());
        responseData.put("nextBeforeMessageId", historyPage.getNextBeforeMessageId());
        responseData.put("latestMessageId", historyPage.getLatestMessageId());
        return responseData;
    }

    static JSONObject buildItem(UserFriendMessageDO message, long todayStart, long yesterdayStart) {
        long messageTime = message.getGmtCreated() != null
                ? message.getGmtCreated().getTime() : System.currentTimeMillis();
        Date messageDate = new Date(messageTime);
        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm");
        SimpleDateFormat fullFormat = new SimpleDateFormat("yyyy-MM-dd");

        String groupTime;
        if (messageTime >= todayStart) {
            groupTime = timeFormat.format(messageDate);
        } else if (messageTime >= yesterdayStart) {
            groupTime = "昨天 " + timeFormat.format(messageDate);
        } else {
            groupTime = fullFormat.format(messageDate);
        }

        JSONObject item = new JSONObject();
        item.put("id", message.getId());
        item.put("senderId", message.getSenderId());
        item.put("receiverId", message.getReceiverId());
        item.put("content", message.getContent());
        item.put("msgType", message.getMsgType());
        item.put("status", message.getStatus());
        item.put("gmtCreated", messageTime);
        item.put("groupTime", groupTime);
        item.put("msgTimeStr", timeFormat.format(messageDate));
        return item;
    }

    static boolean shouldLogPayload(FrameType type) {
        return type != FrameType.CHAT_MSG_HISTORY_RESPONSE;
    }
}
