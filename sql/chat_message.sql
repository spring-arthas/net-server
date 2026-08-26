CREATE TABLE `mds_chat_message` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `sender_id` int(11) NOT NULL COMMENT '发送方用户ID',
  `receiver_id` int(11) NOT NULL COMMENT '接收方用户ID',
  `content` varchar(2000) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '消息内容',
  `msg_type` varchar(20) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'TEXT' COMMENT '消息类型: TEXT, IMAGE, FILE等',
  `status` tinyint(4) NOT NULL DEFAULT '0' COMMENT '状态: 0-未读, 1-已读',
  `del` varchar(2) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'N' COMMENT '是否删除: Y-是, N-否',
  `gmt_created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `gmt_modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  PRIMARY KEY (`id`),
  KEY `idx_sender_receiver` (`sender_id`,`receiver_id`),
  KEY `idx_receiver_status` (`receiver_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='聊天消息记录表';
