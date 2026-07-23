-- Chat history cursor pagination preflight.
SHOW INDEX FROM user_friend_message;

EXPLAIN SELECT *
FROM user_friend_message
WHERE del = 'N'
  AND ((sender_id = 1 AND receiver_id = 2)
    OR (sender_id = 2 AND receiver_id = 1))
ORDER BY id DESC
LIMIT 21;

EXPLAIN SELECT *
FROM user_friend_message
WHERE del = 'N'
  AND ((sender_id = 1 AND receiver_id = 2)
    OR (sender_id = 2 AND receiver_id = 1))
  AND id < 9223372036854775807
ORDER BY id DESC
LIMIT 21;

EXPLAIN SELECT *
FROM user_friend_message
WHERE del = 'N'
  AND ((sender_id = 1 AND receiver_id = 2)
    OR (sender_id = 2 AND receiver_id = 1))
  AND id > 0
ORDER BY id ASC
LIMIT 21;

SET @chat_history_index_exists = (
    SELECT COUNT(1)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'user_friend_message'
      AND index_name = 'idx_chat_pair_del_id'
);

SET @chat_history_index_sql = IF(
    @chat_history_index_exists = 0,
    'ALTER TABLE user_friend_message ADD INDEX idx_chat_pair_del_id (sender_id, receiver_id, del, id)',
    'SELECT ''idx_chat_pair_del_id already exists'' AS migration_status'
);

PREPARE chat_history_index_stmt FROM @chat_history_index_sql;
EXECUTE chat_history_index_stmt;
DEALLOCATE PREPARE chat_history_index_stmt;

SHOW INDEX FROM user_friend_message;
