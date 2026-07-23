-- Read-only audit for IMAGE and MIXED chat attachment references.
-- This script does not update business tables. It reports invalid database
-- references and exposes file_path for a separate filesystem existence check.

DROP TEMPORARY TABLE IF EXISTS tmp_chat_attachment_reference_audit;

CREATE TEMPORARY TABLE tmp_chat_attachment_reference_audit (
    message_id BIGINT NOT NULL,
    msg_type VARCHAR(16) NOT NULL,
    attachment_index INT NOT NULL,
    attachment_field VARCHAR(32) NOT NULL,
    referenced_file_id BIGINT NULL,
    expected_file_name VARCHAR(512) NULL,
    expected_file_size BIGINT NULL
);

INSERT INTO tmp_chat_attachment_reference_audit (
    message_id, msg_type, attachment_index, attachment_field,
    referenced_file_id, expected_file_name, expected_file_size
)
SELECT
    id,
    msg_type,
    0,
    'fileId',
    CAST(JSON_UNQUOTE(JSON_EXTRACT(content, '$.fileId')) AS UNSIGNED),
    JSON_UNQUOTE(JSON_EXTRACT(content, '$.fileName')),
    CAST(JSON_UNQUOTE(JSON_EXTRACT(content, '$.fileSize')) AS UNSIGNED)
FROM user_friend_message
WHERE msg_type = 'IMAGE'
  AND JSON_VALID(content) = 1;

INSERT INTO tmp_chat_attachment_reference_audit (
    message_id, msg_type, attachment_index, attachment_field,
    referenced_file_id, expected_file_name, expected_file_size
)
SELECT
    m.id,
    m.msg_type,
    seq.idx,
    fields.attachment_field,
    CAST(JSON_UNQUOTE(JSON_EXTRACT(
        m.content,
        CONCAT('$.attachments[', seq.idx, '].', fields.attachment_field)
    )) AS UNSIGNED),
    JSON_UNQUOTE(JSON_EXTRACT(m.content, CONCAT('$.attachments[', seq.idx, '].fileName'))),
    CAST(JSON_UNQUOTE(JSON_EXTRACT(
        m.content,
        CONCAT(
            '$.attachments[', seq.idx, '].',
            CASE fields.attachment_field
                WHEN 'thumbnailFileId' THEN 'thumbnailFileSize'
                WHEN 'previewFileId' THEN 'previewFileSize'
                ELSE 'fileSize'
            END
        )
    )) AS UNSIGNED)
FROM (
    SELECT id, msg_type, content
    FROM user_friend_message
    WHERE msg_type = 'MIXED'
      AND JSON_VALID(content) = 1
) m
CROSS JOIN (
    SELECT 0 AS idx UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL
    SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL
    SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8
) seq
CROSS JOIN (
    SELECT 'fileId' AS attachment_field
    UNION ALL SELECT 'thumbnailFileId'
    UNION ALL SELECT 'previewFileId'
) fields
WHERE seq.idx < JSON_LENGTH(JSON_EXTRACT(m.content, '$.attachments'))
  AND JSON_EXTRACT(
      m.content,
      CONCAT('$.attachments[', seq.idx, '].', fields.attachment_field)
  ) IS NOT NULL;

SELECT
    audit.message_id,
    audit.msg_type,
    audit.attachment_index,
    audit.attachment_field,
    audit.referenced_file_id,
    audit.expected_file_name,
    audit.expected_file_size,
    f.file_name AS actual_file_name,
    f.file_size AS actual_file_size,
    f.file_path,
    f.user_id,
    f.del,
    f.is_exist,
    f.is_file,
    CASE
        WHEN audit.referenced_file_id IS NULL OR audit.referenced_file_id <= 0 THEN 'INVALID_ID'
        WHEN f.id IS NULL THEN 'DB_FILE_NOT_FOUND'
        WHEN f.del = 'Y' THEN 'LOGICALLY_DELETED'
        WHEN f.is_exist <> 'Y' THEN 'MARKED_NOT_EXIST'
        WHEN f.is_file <> 'Y' THEN 'NOT_A_FILE'
        WHEN audit.expected_file_name IS NOT NULL AND audit.expected_file_name <> f.file_name THEN 'FILE_NAME_MISMATCH'
        WHEN audit.expected_file_size IS NOT NULL AND audit.expected_file_size <> f.file_size THEN 'FILE_SIZE_MISMATCH'
        ELSE 'DB_REFERENCE_OK_CHECK_FILE_PATH'
    END AS audit_status
FROM tmp_chat_attachment_reference_audit audit
LEFT JOIN file f ON f.id = audit.referenced_file_id
WHERE audit.referenced_file_id IS NULL
   OR audit.referenced_file_id <= 0
   OR f.id IS NULL
   OR f.del = 'Y'
   OR f.is_exist <> 'Y'
   OR f.is_file <> 'Y'
   OR (audit.expected_file_name IS NOT NULL AND audit.expected_file_name <> f.file_name)
   OR (audit.expected_file_size IS NOT NULL AND audit.expected_file_size <> f.file_size)
ORDER BY audit.message_id, audit.attachment_index, audit.attachment_field;

-- Run this second result set to obtain every valid DB reference and verify
-- each returned file_path on the net-server host. SQL cannot prove whether a
-- path exists on disk.
SELECT
    audit.message_id,
    audit.attachment_index,
    audit.attachment_field,
    audit.referenced_file_id,
    f.file_path
FROM tmp_chat_attachment_reference_audit audit
JOIN file f ON f.id = audit.referenced_file_id
WHERE f.del = 'N' AND f.is_exist = 'Y' AND f.is_file = 'Y'
ORDER BY audit.message_id, audit.attachment_index, audit.attachment_field;
