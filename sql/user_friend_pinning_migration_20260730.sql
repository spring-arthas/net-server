-- 账号级好友置顶字段与排序索引（MySQL 8，幂等执行）。

SET @friend_pin_column_exists = (
    SELECT COUNT(1)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'user_friends'
      AND COLUMN_NAME = 'is_pinned'
);
SET @friend_pin_column_sql = IF(
    @friend_pin_column_exists = 0,
    'ALTER TABLE user_friends ADD COLUMN is_pinned TINYINT(1) NOT NULL DEFAULT 0 AFTER alias',
    'SELECT 1'
);
PREPARE friend_pin_column_stmt FROM @friend_pin_column_sql;
EXECUTE friend_pin_column_stmt;
DEALLOCATE PREPARE friend_pin_column_stmt;

SET @friend_pinned_at_column_exists = (
    SELECT COUNT(1)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'user_friends'
      AND COLUMN_NAME = 'pinned_at'
);
SET @friend_pinned_at_column_sql = IF(
    @friend_pinned_at_column_exists = 0,
    'ALTER TABLE user_friends ADD COLUMN pinned_at DATETIME(3) NULL AFTER is_pinned',
    'SELECT 1'
);
PREPARE friend_pinned_at_column_stmt FROM @friend_pinned_at_column_sql;
EXECUTE friend_pinned_at_column_stmt;
DEALLOCATE PREPARE friend_pinned_at_column_stmt;

SET @friend_pin_index_exists = (
    SELECT COUNT(1)
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'user_friends'
      AND INDEX_NAME = 'idx_user_friends_pin_order'
);
SET @friend_pin_index_sql = IF(
    @friend_pin_index_exists = 0,
    'ALTER TABLE user_friends ADD INDEX idx_user_friends_pin_order (user_id, del, is_pinned, pinned_at)',
    'SELECT 1'
);
PREPARE friend_pin_index_stmt FROM @friend_pin_index_sql;
EXECUTE friend_pin_index_stmt;
DEALLOCATE PREPARE friend_pin_index_stmt;
