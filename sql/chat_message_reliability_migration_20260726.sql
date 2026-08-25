-- Optional additive migration for persistent client message IDs and quotes.
-- The server remains backward compatible before this script is applied, but
-- quote metadata will only survive history reloads after these columns exist.

SET @schema_name = DATABASE();

SET @sql = IF(
    EXISTS(
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = @schema_name
          AND table_name = 'user_friend_message'
          AND column_name = 'client_msg_id'
    ),
    'SELECT 1',
    'ALTER TABLE user_friend_message ADD COLUMN client_msg_id VARCHAR(64) NULL COMMENT ''客户端消息ID'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS(
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = @schema_name
          AND table_name = 'user_friend_message'
          AND column_name = 'quote_msg_id'
    ),
    'SELECT 1',
    'ALTER TABLE user_friend_message ADD COLUMN quote_msg_id BIGINT NULL COMMENT ''引用消息ID'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS(
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = @schema_name
          AND table_name = 'user_friend_message'
          AND column_name = 'quote_msg_content'
    ),
    'SELECT 1',
    'ALTER TABLE user_friend_message ADD COLUMN quote_msg_content VARCHAR(1000) NULL COMMENT ''引用消息摘要'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS(
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = @schema_name
          AND table_name = 'user_friend_message'
          AND column_name = 'quote_msg_sender_name'
    ),
    'SELECT 1',
    'ALTER TABLE user_friend_message ADD COLUMN quote_msg_sender_name VARCHAR(128) NULL COMMENT ''引用消息发送者'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS(
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = @schema_name
          AND table_name = 'user_friend_message'
          AND index_name = 'idx_chat_client_msg_id'
    ),
    'SELECT 1',
    'ALTER TABLE user_friend_message ADD INDEX idx_chat_client_msg_id (client_msg_id)'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
