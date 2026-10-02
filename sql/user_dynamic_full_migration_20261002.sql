-- ============================================================
-- 动态功能完整迁移脚本（MySQL 8，幂等、只增不删）
-- 包含：动态表、互动表、楼中楼回复 parent_id 字段
-- ============================================================

-- 1. 创建动态表
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

-- 2. 动态表字段迁移（兼容旧表）
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

-- 3. 创建动态互动表（点赞、评论、转发）
CREATE TABLE IF NOT EXISTS `user_dynamic_interaction` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `dynamic_id` BIGINT NOT NULL,
    `user_id` BIGINT NOT NULL,
    `action_type` VARCHAR(16) NOT NULL,
    `content` VARCHAR(280) NULL,
    `parent_id` BIGINT NULL,
    `idempotency_key` VARCHAR(128) NOT NULL,
    `del` CHAR(1) NOT NULL DEFAULT 'N',
    `del_time` DATETIME(3) NULL,
    `gmt_created` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `gmt_modified` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_dynamic_interaction_idempotency` (`idempotency_key`),
    KEY `idx_user_dynamic_interaction_counts` (`dynamic_id`, `action_type`, `del`),
    KEY `idx_user_dynamic_reply_cursor` (`dynamic_id`, `action_type`, `del`, `id`),
    KEY `idx_user_dynamic_interaction_user` (`user_id`, `dynamic_id`, `del`),
    KEY `idx_user_dynamic_interaction_parent` (`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 4. 互动表 parent_id 字段迁移（兼容已存在的旧表）
SET @interaction_parent_id_column_exists = (
    SELECT COUNT(1) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_dynamic_interaction'
      AND COLUMN_NAME = 'parent_id'
);
SET @interaction_parent_id_column_sql = IF(
    @interaction_parent_id_column_exists = 0,
    'ALTER TABLE user_dynamic_interaction ADD COLUMN parent_id BIGINT NULL AFTER content',
    'SELECT 1'
);
PREPARE interaction_parent_id_column_stmt FROM @interaction_parent_id_column_sql;
EXECUTE interaction_parent_id_column_stmt;
DEALLOCATE PREPARE interaction_parent_id_column_stmt;

SET @interaction_parent_id_index_exists = (
    SELECT COUNT(1) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_dynamic_interaction'
      AND INDEX_NAME = 'idx_user_dynamic_interaction_parent'
);
SET @interaction_parent_id_index_sql = IF(
    @interaction_parent_id_index_exists = 0,
    'ALTER TABLE user_dynamic_interaction ADD INDEX idx_user_dynamic_interaction_parent (parent_id)',
    'SELECT 1'
);
PREPARE interaction_parent_id_index_stmt FROM @interaction_parent_id_index_sql;
EXECUTE interaction_parent_id_index_stmt;
DEALLOCATE PREPARE interaction_parent_id_index_stmt;

-- 完成
SELECT '迁移完成：user_dynamic、user_dynamic_interaction（含 parent_id）已就绪' AS result;
