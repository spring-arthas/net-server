-- 将用户 18806504525 的 Windows 存储路径从 E 盘迁移到 F 盘。
-- 执行前请确认物理文件已完整迁移到：
-- F:\storage\upload\file\18806504525
--
-- 本脚本只更新 file/file_task 中以指定旧目录为前缀的记录，
-- 不会修改其他用户、其他目录或 Linux/macOS 路径。

SET @old_prefix = 'E:\\storage\\upload\\file\\18806504525';
SET @new_prefix = 'F:\\storage\\upload\\file\\18806504525';

-- 执行前预览影响范围
SELECT 'file' AS table_name, COUNT(*) AS affected_rows
FROM file
WHERE LEFT(file_path, CHAR_LENGTH(@old_prefix)) = @old_prefix;

SELECT 'file_task' AS table_name, COUNT(*) AS affected_rows
FROM file_task
WHERE LEFT(file_path, CHAR_LENGTH(@old_prefix)) = @old_prefix;

START TRANSACTION;

UPDATE file
SET file_path = CONCAT(@new_prefix,
                        SUBSTRING(file_path, CHAR_LENGTH(@old_prefix) + 1)),
    gmt_modified = NOW()
WHERE LEFT(file_path, CHAR_LENGTH(@old_prefix)) = @old_prefix;

UPDATE file_task
SET file_path = CONCAT(@new_prefix,
                        SUBSTRING(file_path, CHAR_LENGTH(@old_prefix) + 1)),
    gmt_modified = NOW()
WHERE LEFT(file_path, CHAR_LENGTH(@old_prefix)) = @old_prefix;

-- 提交前确认已没有旧前缀记录
SELECT 'file' AS table_name, COUNT(*) AS remaining_old_rows
FROM file
WHERE LEFT(file_path, CHAR_LENGTH(@old_prefix)) = @old_prefix;

SELECT 'file_task' AS table_name, COUNT(*) AS remaining_old_rows
FROM file_task
WHERE LEFT(file_path, CHAR_LENGTH(@old_prefix)) = @old_prefix;

COMMIT;
