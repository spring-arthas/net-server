package com.alibaba.server.nio.service.file.handler;

import com.alibaba.fastjson.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FriendListItemComparatorTest {

    @Test
    public void sortsByPinTimeUnreadOnlineNameAndFriendId() {
        List<JSONObject> items = new ArrayList<>(Arrays.asList(
                item(1L, 91, false, null, 9, true, "A"),
                item(2L, 92, true, 100L, 0, false, "Z"),
                item(3L, 93, true, 200L, 0, false, "Z"),
                item(4L, 94, false, null, 1, false, "Z"),
                item(5L, 95, false, null, 0, true, "Z"),
                item(6L, 97, false, null, 0, false, "beta"),
                item(7L, 96, false, null, 0, false, "Alpha"),
                item(8L, 95, false, null, 0, false, "Alpha")
        ));

        items.sort(FriendListItemComparator.INSTANCE);

        assertEquals(Arrays.asList(3L, 2L, 1L, 4L, 5L, 8L, 7L, 6L),
                items.stream().map(value -> value.getLong("id")).collect(Collectors.toList()));
    }

    @Test
    public void friendListResponseIncludesCanonicalPinFieldsAndSorts() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/alibaba/server/nio/service/file/handler/TextTransmissionHandler.java")),
                StandardCharsets.UTF_8);
        String handler = source.substring(source.indexOf("private void handleFriendList("));
        handler = handler.substring(0, handler.indexOf("\n    private void ", 1));

        assertTrue(handler.contains("item.put(\"pinned\""));
        assertTrue(handler.contains("item.put(\"pinnedAt\""));
        assertTrue(handler.contains("resultList.sort(FriendListItemComparator.INSTANCE)"));
        assertFalse(handler.contains("friend.setAlias("));
    }

    @Test
    public void effectiveDisplayNameFallsBackToNicknameThenUsername() {
        JSONObject nickname = item(1L, 91, false, null, 0, false, "");
        nickname.put("nickName", "Beta");
        nickname.put("userName", "z-user");
        JSONObject username = item(2L, 92, false, null, 0, false, null);
        username.put("userName", "Alpha");
        List<JSONObject> items = new ArrayList<>(Arrays.asList(nickname, username));

        items.sort(FriendListItemComparator.INSTANCE);

        assertEquals(Arrays.asList(2L, 1L),
                items.stream().map(value -> value.getLong("id")).collect(Collectors.toList()));
    }

    private JSONObject item(Long id, int friendId, boolean pinned, Long pinnedAt,
            int unreadCount, boolean online, String alias) {
        JSONObject value = new JSONObject();
        value.put("id", id);
        value.put("friendId", friendId);
        value.put("pinned", pinned);
        value.put("pinnedAt", pinnedAt);
        value.put("unreadCount", unreadCount);
        value.put("online", online);
        value.put("alias", alias);
        return value;
    }
}
