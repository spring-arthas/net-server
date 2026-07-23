-- Repair one historical chat attachment message whose JSON references a chat
-- message id (452) instead of the actual file ids. This script is intentionally
-- scoped to message 453 and aborts unless all three candidate files are unique.

START TRANSACTION;

SET collation_connection = 'utf8mb4_general_ci';

CREATE TABLE IF NOT EXISTS user_friend_message_content_repair_backup (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    message_id BIGINT NOT NULL,
    old_content VARCHAR(2000) NOT NULL,
    repair_reason VARCHAR(255) NOT NULL,
    repaired_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_message_reason (message_id, repair_reason)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @message_id := 453;
SET @bad_file_id := 452;
SET @repair_reason := '20260721_chat_attachment_file_ids';

SET @original_id := (
    SELECT id
    FROM file
    WHERE user_id = 5
      AND del = 'N'
      AND is_exist = 'Y'
      AND is_file = 'Y'
      AND file_name = '2025转入.jpg'
      AND file_size = 3148898
    ORDER BY gmt_created DESC
    LIMIT 1
);

SET @thumb_id := (
    SELECT id
    FROM file
    WHERE user_id = 5
      AND del = 'N'
      AND is_exist = 'Y'
      AND is_file = 'Y'
      AND file_name LIKE 'chat-thumb-%'
      AND file_size = 19270
    ORDER BY gmt_created DESC
    LIMIT 1
);

SET @preview_id := (
    SELECT id
    FROM file
    WHERE user_id = 5
      AND del = 'N'
      AND is_exist = 'Y'
      AND is_file = 'Y'
      AND file_name LIKE 'chat-preview-%'
      AND file_size = 212489
    ORDER BY gmt_created DESC
    LIMIT 1
);

SET @original_candidate_count := (
    SELECT COUNT(1)
    FROM file
    WHERE user_id = 5
      AND del = 'N'
      AND is_exist = 'Y'
      AND is_file = 'Y'
      AND file_name = '2025转入.jpg'
      AND file_size = 3148898
);

SET @thumb_candidate_count := (
    SELECT COUNT(1)
    FROM file
    WHERE user_id = 5
      AND del = 'N'
      AND is_exist = 'Y'
      AND is_file = 'Y'
      AND file_name LIKE 'chat-thumb-%'
      AND file_size = 19270
);

SET @preview_candidate_count := (
    SELECT COUNT(1)
    FROM file
    WHERE user_id = 5
      AND del = 'N'
      AND is_exist = 'Y'
      AND is_file = 'Y'
      AND file_name LIKE 'chat-preview-%'
      AND file_size = 212489
);

INSERT IGNORE INTO user_friend_message_content_repair_backup (
    message_id,
    old_content,
    repair_reason
)
SELECT id, content, @repair_reason
FROM user_friend_message
WHERE id = @message_id
  AND msg_type = 'MIXED'
  AND @original_candidate_count = 1
  AND @thumb_candidate_count = 1
  AND @preview_candidate_count = 1
  AND content LIKE CONCAT('%"fileId":', @bad_file_id, '%')
  AND content LIKE CONCAT('%"thumbnailFileId":', @bad_file_id, '%')
  AND content LIKE CONCAT('%"previewFileId":', @bad_file_id, '%');

UPDATE user_friend_message
SET content = REPLACE(
        REPLACE(
            REPLACE(content,
                CONCAT('"thumbnailFileId":', @bad_file_id),
                CONCAT('"thumbnailFileId":', @thumb_id)),
            CONCAT('"previewFileId":', @bad_file_id),
            CONCAT('"previewFileId":', @preview_id)),
        CONCAT('"fileId":', @bad_file_id),
        CONCAT('"fileId":', @original_id)),
    gmt_modified = NOW()
WHERE id = @message_id
  AND msg_type = 'MIXED'
  AND @original_candidate_count = 1
  AND @thumb_candidate_count = 1
  AND @preview_candidate_count = 1
  AND @original_id IS NOT NULL
  AND @thumb_id IS NOT NULL
  AND @preview_id IS NOT NULL
  AND content LIKE CONCAT('%"fileId":', @bad_file_id, '%')
  AND content LIKE CONCAT('%"thumbnailFileId":', @bad_file_id, '%')
  AND content LIKE CONCAT('%"previewFileId":', @bad_file_id, '%');

SELECT
    @original_candidate_count AS original_candidate_count,
    @thumb_candidate_count AS thumbnail_candidate_count,
    @preview_candidate_count AS preview_candidate_count,
    @original_id AS original_file_id,
    @thumb_id AS thumbnail_file_id,
    @preview_id AS preview_file_id,
    ROW_COUNT() AS updated_rows;

SELECT id, msg_type, content
FROM user_friend_message
WHERE id = @message_id;

COMMIT;
