-- 好友添加安全与一致性改造（MySQL 8）
-- 执行前应先处理下方审计 SQL 返回的异常数据。

-- 1. 数据审计：这些查询应在迁移前人工确认。
SELECT a.id, a.sender_id, a.receiver_id, a.status, a.del
FROM user_friend_apply a
LEFT JOIN user sender ON sender.id = a.sender_id AND sender.del = 'N'
LEFT JOIN user receiver ON receiver.id = a.receiver_id AND receiver.del = 'N'
WHERE a.del = 'N' AND (sender.id IS NULL OR receiver.id IS NULL);

SELECT sender_id, receiver_id, COUNT(*) AS pending_count
FROM user_friend_apply
WHERE status = 0 AND del = 'N'
GROUP BY sender_id, receiver_id
HAVING COUNT(*) > 1;

SELECT f.id, f.user_id, f.friend_id, f.del
FROM user_friends f
LEFT JOIN user_friends reverse_relation
  ON reverse_relation.user_id = f.friend_id
 AND reverse_relation.friend_id = f.user_id
 AND reverse_relation.del = 'N'
WHERE f.del = 'N' AND reverse_relation.id IS NULL;

-- 2. 仅约束有效待处理申请。已同意或已拒绝记录仍可保留历史。
SET @pending_column_exists = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'user_friend_apply'
      AND COLUMN_NAME = 'pending_key'
);
SET @sql = IF(
    @pending_column_exists = 0,
    'ALTER TABLE user_friend_apply ADD COLUMN pending_key VARCHAR(80) GENERATED ALWAYS AS (CASE WHEN status = 0 AND del = ''N'' THEN CONCAT(sender_id, '':'', receiver_id) ELSE NULL END) STORED',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @pending_index_exists = (
    SELECT COUNT(*)
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'user_friend_apply'
      AND INDEX_NAME = 'uk_friend_apply_pending'
);
SET @sql = IF(
    @pending_index_exists = 0,
    'ALTER TABLE user_friend_apply ADD UNIQUE KEY uk_friend_apply_pending (pending_key)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3. 支持按发送者与时间窗口进行频率限制。
SET @rate_index_exists = (
    SELECT COUNT(*)
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'user_friend_apply'
      AND INDEX_NAME = 'idx_friend_apply_sender_created'
);
SET @sql = IF(
    @rate_index_exists = 0,
    'ALTER TABLE user_friend_apply ADD KEY idx_friend_apply_sender_created (sender_id, gmt_created)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
