-- 动态时间线、媒体引用及互动支持（MySQL 8，幂等、只增不删）。
-- 本文件只生成迁移，不由应用自动执行。

CREATE TABLE IF NOT EXISTS `user_dynamic` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `content` VARCHAR(500) NOT NULL DEFAULT '',
    `image_paths` TEXT NULL,
    `del` CHAR(1) NOT NULL DEFAULT 'N',
    `del_time` DATETIME(3) NULL,
    `gmt_created` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `gmt_modified` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    KEY `idx_user_dynamic_user_cursor` (`user_id`, `del`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- [修改] 兼容现网旧表：旧 user_dynamic 没有 del_time，新 Mapper 的查询和逻辑删除需要该列。
SET @dynamic_del_time_column_exists = (
    SELECT COUNT(1) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_dynamic'
      AND COLUMN_NAME = 'del_time'
);
SET @dynamic_del_time_column_sql = IF(
    @dynamic_del_time_column_exists = 0,
    'ALTER TABLE user_dynamic ADD COLUMN del_time DATETIME(3) NULL AFTER del',
    'SELECT 1'
);
PREPARE dynamic_del_time_column_stmt FROM @dynamic_del_time_column_sql;
EXECUTE dynamic_del_time_column_stmt;
DEALLOCATE PREPARE dynamic_del_time_column_stmt;

SET @dynamic_media_json_column_exists = (
    SELECT COUNT(1) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_dynamic'
      AND COLUMN_NAME = 'media_json'
);
SET @dynamic_media_json_column_sql = IF(
    @dynamic_media_json_column_exists = 0,
    'ALTER TABLE user_dynamic ADD COLUMN media_json JSON NULL AFTER image_paths',
    'SELECT 1'
);
PREPARE dynamic_media_json_column_stmt FROM @dynamic_media_json_column_sql;
EXECUTE dynamic_media_json_column_stmt;
DEALLOCATE PREPARE dynamic_media_json_column_stmt;

SET @dynamic_reference_json_column_exists = (
    SELECT COUNT(1) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_dynamic'
      AND COLUMN_NAME = 'reference_json'
);
SET @dynamic_reference_json_column_sql = IF(
    @dynamic_reference_json_column_exists = 0,
    'ALTER TABLE user_dynamic ADD COLUMN reference_json JSON NULL AFTER media_json',
    'SELECT 1'
);
PREPARE dynamic_reference_json_column_stmt FROM @dynamic_reference_json_column_sql;
EXECUTE dynamic_reference_json_column_stmt;
DEALLOCATE PREPARE dynamic_reference_json_column_stmt;

SET @dynamic_cursor_index_exists = (
    SELECT COUNT(1) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_dynamic'
      AND INDEX_NAME = 'idx_user_dynamic_user_cursor'
);
SET @dynamic_cursor_index_sql = IF(
    @dynamic_cursor_index_exists = 0,
    'ALTER TABLE user_dynamic ADD INDEX idx_user_dynamic_user_cursor (user_id, del, id)',
    'SELECT 1'
);
PREPARE dynamic_cursor_index_stmt FROM @dynamic_cursor_index_sql;
EXECUTE dynamic_cursor_index_stmt;
DEALLOCATE PREPARE dynamic_cursor_index_stmt;

CREATE TABLE IF NOT EXISTS `user_dynamic_interaction` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `dynamic_id` BIGINT NOT NULL,
    `user_id` BIGINT NOT NULL,
    `action_type` VARCHAR(16) NOT NULL,
    `content` VARCHAR(280) NULL,
    `idempotency_key` VARCHAR(128) NOT NULL,
    `del` CHAR(1) NOT NULL DEFAULT 'N',
    `del_time` DATETIME(3) NULL,
    `gmt_created` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `gmt_modified` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_dynamic_interaction_idempotency` (`idempotency_key`),
    KEY `idx_user_dynamic_interaction_counts` (`dynamic_id`, `action_type`, `del`),
    KEY `idx_user_dynamic_reply_cursor` (`dynamic_id`, `action_type`, `del`, `id`),
    KEY `idx_user_dynamic_interaction_user` (`user_id`, `dynamic_id`, `del`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
