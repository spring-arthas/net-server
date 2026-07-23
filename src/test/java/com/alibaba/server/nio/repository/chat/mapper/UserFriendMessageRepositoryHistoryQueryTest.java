package com.alibaba.server.nio.repository.chat.mapper;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.apache.ibatis.annotations.Select;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class UserFriendMessageRepositoryHistoryQueryTest {

    @Test
    public void latestQueryFiltersDeletedRowsAndUsesDescendingIdOrder() throws Exception {
        String sql = selectSql("getLatestChatHistory",
                Integer.class, Integer.class, int.class);

        assertConversationFilter(sql);
        assertTrue(sql.contains("del = 'N'"));
        assertTrue(sql.contains("ORDER BY id DESC"));
        assertTrue(sql.contains("LIMIT #{limit}"));
    }

    @Test
    public void beforeQueryFiltersDeletedRowsAndUsesDescendingIdCursor() throws Exception {
        String sql = selectSql("getChatHistoryBefore",
                Integer.class, Integer.class, Long.class, int.class);

        assertConversationFilter(sql);
        assertTrue(sql.contains("del = 'N'"));
        assertTrue(sql.contains("id < #{beforeMessageId}"));
        assertTrue(sql.contains("ORDER BY id DESC"));
        assertTrue(sql.contains("LIMIT #{limit}"));
    }

    @Test
    public void afterQueryFiltersDeletedRowsAndUsesAscendingIdCursor() throws Exception {
        String sql = selectSql("getChatHistoryAfter",
                Integer.class, Integer.class, Long.class, int.class);

        assertConversationFilter(sql);
        assertTrue(sql.contains("del = 'N'"));
        assertTrue(sql.contains("id > #{afterMessageId}"));
        assertTrue(sql.contains("ORDER BY id ASC"));
        assertTrue(sql.contains("LIMIT #{limit}"));
    }

    @Test
    public void legacyQueryNowFiltersDeletedRows() throws Exception {
        String sql = selectSql("getChatHistory",
                Integer.class, Integer.class, int.class, int.class);

        assertConversationFilter(sql);
        assertTrue(sql.contains("del = 'N'"));
    }

    @Test
    public void cursorIndexMigrationIsIdempotentAndTargetsLiveTable() throws Exception {
        String migration = new String(Files.readAllBytes(Paths.get(
                "sql/chat_history_cursor_migration_20260722.sql")), StandardCharsets.UTF_8);

        assertTrue(migration.contains("information_schema.statistics"));
        assertTrue(migration.contains("idx_chat_pair_del_id"));
        assertTrue(migration.contains("user_friend_message"));
        assertTrue(migration.contains("sender_id, receiver_id, del, id"));
        assertTrue(migration.contains("PREPARE"));
        assertTrue(migration.contains("SHOW INDEX FROM user_friend_message"));
        assertTrue(migration.contains("EXPLAIN"));
    }

    private static String selectSql(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = UserFriendMessageRepository.class.getMethod(methodName, parameterTypes);
        Select select = method.getAnnotation(Select.class);
        return String.join(" ", select.value()).replaceAll("\\s+", " ");
    }

    private static void assertConversationFilter(String sql) {
        assertTrue(sql.contains("sender_id = #{userId1}"));
        assertTrue(sql.contains("receiver_id = #{userId2}"));
        assertTrue(sql.contains("sender_id = #{userId2}"));
        assertTrue(sql.contains("receiver_id = #{userId1}"));
    }
}
