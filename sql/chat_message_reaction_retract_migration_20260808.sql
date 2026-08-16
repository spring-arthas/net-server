-- 聊天消息表情回应与撤回支持（2026-08-08）
-- 对应第二阶段「单聊效率」：表情回应、消息搜索
-- 执行前请确认线上实际使用的表名（user_friend_message 或 mds_chat_message），
-- 与 UserFriendMessageRepository 的查询表名保持一致。

ALTER TABLE `user_friend_message`
    ADD COLUMN `reaction` TEXT NULL COMMENT '表情回应 JSON, 形如 {"emoji":[userId1,userId2]}' AFTER `status`,
    ADD COLUMN `retracted` TINYINT NOT NULL DEFAULT 0 COMMENT '是否撤回: 0-否, 1-已撤回' AFTER `reaction`;

-- 搜索索引（关键词模糊搜索）
CREATE INDEX `idx_user_friend_message_content` ON `user_friend_message` (`content`(191));
