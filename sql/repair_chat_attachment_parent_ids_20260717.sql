-- 家校测试库聊天附件目录修复。
-- 由 scripts/repair_chat_attachment_files_20260717.sh 在文件移动完成后执行。
-- 调用方必须先设置 @recovered_preview_size。

DELIMITER $$

DROP PROCEDURE IF EXISTS repair_chat_attachment_parent_ids_20260717$$
CREATE PROCEDURE repair_chat_attachment_parent_ids_20260717()
BEGIN
    DECLARE affected_files INT DEFAULT 0;
    DECLARE invalid_files INT DEFAULT 0;
    DECLARE dir_user_7 BIGINT DEFAULT NULL;
    DECLARE dir_user_15 BIGINT DEFAULT NULL;

    SELECT COUNT(*) INTO invalid_files
    FROM file
    WHERE id IN (
        7471493778193281024, 7471494081969942528, 7471510898411077632,
        7471510901426782208, 7471510903746232320, 7471510905759498240,
        7471510908666150912, 7471510911010766848, 7471510912571047936,
        7471510914668199936, 7471510920267595776, 7471510923077779456,
        7471510925481115648, 7471510927490187264, 7471515448564031488,
        7471515454377336832, 7471515456906502144, 7471515459431473152,
        7471515461746728960, 7471515463139237888, 7471515464619827200,
        7471515466469515264, 7471515470772871168, 7471553120644907008,
        7471553127687143424, 7471553129687826432, 7471553131927584768,
        7471553134985232384, 7471553136914612224
    )
      AND is_file = 'Y'
      AND is_exist = 'Y'
      AND del = 'N'
      AND parent_id IN (0, -1)
      AND user_id IS NULL
      AND user_name IN ('18806504525', '15757821544');

    IF invalid_files <> 29 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = '迁移前置校验失败：异常聊天附件记录不再是预期的29条';
    END IF;
    IF @recovered_preview_size IS NULL OR @recovered_preview_size <= 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = '迁移前置校验失败：恢复后的预览图大小无效';
    END IF;

    START TRANSACTION;

    SELECT id INTO dir_user_7
    FROM file
    WHERE parent_id = 7416342564812169216
      AND file_name = '.chat-attachments'
      AND user_id = 7 AND is_file = 'N' AND is_exist = 'Y' AND del = 'N'
    LIMIT 1 FOR UPDATE;

    IF dir_user_7 IS NULL THEN
        SELECT MAX(id) + 1 INTO dir_user_7 FROM file FOR UPDATE;
        INSERT INTO file (
            id, parent_id, file_name, file_path, file_type, file_size,
            is_file, is_exist, has_child, user_id, user_name,
            del, del_time, gmt_created, gmt_modified
        ) VALUES (
            dir_user_7, 7416342564812169216, '.chat-attachments',
            '/Users/hljy/Documents/storages/18806504525/.chat-attachments',
            'NOT_FILE', 0, 'N', 'Y', 'N', 7, '18806504525',
            'N', NULL, NOW(), NOW()
        );
    END IF;

    SELECT id INTO dir_user_15
    FROM file
    WHERE parent_id = 7433007779691900928
      AND file_name = '.chat-attachments'
      AND user_id = 15 AND is_file = 'N' AND is_exist = 'Y' AND del = 'N'
    LIMIT 1 FOR UPDATE;

    IF dir_user_15 IS NULL THEN
        SELECT MAX(id) + 1 INTO dir_user_15 FROM file FOR UPDATE;
        INSERT INTO file (
            id, parent_id, file_name, file_path, file_type, file_size,
            is_file, is_exist, has_child, user_id, user_name,
            del, del_time, gmt_created, gmt_modified
        ) VALUES (
            dir_user_15, 7433007779691900928, '.chat-attachments',
            '/Users/hljy/Documents/storages/15757821544/.chat-attachments',
            'NOT_FILE', 0, 'N', 'Y', 'N', 15, '15757821544',
            'N', NULL, NOW(), NOW()
        );
    END IF;

    UPDATE file
    SET parent_id = CASE user_name
            WHEN '18806504525' THEN dir_user_7
            WHEN '15757821544' THEN dir_user_15
        END,
        user_id = CASE user_name
            WHEN '18806504525' THEN 7
            WHEN '15757821544' THEN 15
        END,
        file_path = CONCAT(
            '/Users/hljy/Documents/storages/', user_name,
            '/.chat-attachments/', SUBSTRING_INDEX(file_path, '/', -1)
        ),
        file_size = CASE id
            WHEN 7471510911010766848 THEN @recovered_preview_size
            ELSE file_size
        END,
        gmt_modified = NOW()
    WHERE id IN (
        7471493778193281024, 7471494081969942528, 7471510898411077632,
        7471510901426782208, 7471510903746232320, 7471510905759498240,
        7471510908666150912, 7471510911010766848, 7471510912571047936,
        7471510914668199936, 7471510920267595776, 7471510923077779456,
        7471510925481115648, 7471510927490187264, 7471515448564031488,
        7471515454377336832, 7471515456906502144, 7471515459431473152,
        7471515461746728960, 7471515463139237888, 7471515464619827200,
        7471515466469515264, 7471515470772871168, 7471553120644907008,
        7471553127687143424, 7471553129687826432, 7471553131927584768,
        7471553134985232384, 7471553136914612224
    )
      AND parent_id IN (0, -1)
      AND user_id IS NULL;

    SET affected_files = ROW_COUNT();
    IF affected_files <> 29 THEN
        ROLLBACK;
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = '迁移失败：实际更新的附件记录不是29条';
    END IF;

    UPDATE user_friend_message
    SET content = JSON_SET(content, '$.attachments[1].previewFileSize', @recovered_preview_size),
        gmt_modified = NOW()
    WHERE id = 227
      AND JSON_EXTRACT(content, '$.attachments[1].previewFileId') = 7471510911010766848;

    IF ROW_COUNT() <> 1 THEN
        ROLLBACK;
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = '迁移失败：损坏预览图的消息引用未正确更新';
    END IF;

    UPDATE file
    SET has_child = 'Y', gmt_modified = NOW()
    WHERE id IN (7416342564812169216, 7433007779691900928);

    COMMIT;

    SELECT dir_user_7 AS user_7_chat_attachment_dir_id,
           dir_user_15 AS user_15_chat_attachment_dir_id,
           affected_files AS migrated_file_count;
END$$

DELIMITER ;

CALL repair_chat_attachment_parent_ids_20260717();
DROP PROCEDURE repair_chat_attachment_parent_ids_20260717;

