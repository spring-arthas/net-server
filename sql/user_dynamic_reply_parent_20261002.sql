-- 动态评论楼中楼回复支持（MySQL 8，幂等、只增不删）。
-- 本文件只生成迁移，不由应用自动执行。

-- 为 user_dynamic_interaction 表增加 parent_id 字段，用于标识回复型评论的父评论ID
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

-- 为 parent_id 增加索引，加速楼中楼查询
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
