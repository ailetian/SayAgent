-- K0808 T7：回答点踩/点赞反馈表（仅 MySQL）。
-- 模板严格遵循 AGENTS.md §6.1 通用字段（id/created_at/updated_at）与 §6.2 索引纪律。
-- 说明：
--   1) rating 用枚举字符串 'THUMBS_UP' / 'THUMBS_DOWN'（§7.2 禁魔法数字），不存 0/1 字面量。
--   2) 唯一键 uk_message_user (message_id, user_id)：同一用户对同一条消息重复点 = 覆盖写（upsert），
--      避免「同一个赞/踩被记成多行」的脏数据。
--   3) 【刻意省略 deleted 软删字段】：本表是「用户对某消息的最新态度」覆盖记录，upsert 已保证「同人机同消息只一行」，
--      不存在「软删后还能留历史」的需求；若未来需要审计「态度变更轨迹」，应另建历史表，而非在本表加 deleted。
--      故实体 MessageFeedback 也不继承 BaseEntity（避免 Hibernate 去插不存在的 deleted 列）。
--   4) reason 仅踩（THUMBS_DOWN）时填，赞时为 NULL。
--   5) agent_id / kb_id 为逻辑外键（VARCHAR(50) / BIGINT），便于按范围聚合「热门被踩」（T13 健康报告用）。

CREATE TABLE `message_feedback` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `message_id` BIGINT UNSIGNED NOT NULL COMMENT '被评价的消息（→message.id）',
  `user_id`    BIGINT UNSIGNED NOT NULL COMMENT '评价者（→user.id）',
  `agent_id`   VARCHAR(50) DEFAULT NULL COMMENT '评价所用 Agent（→conversation.agent_id 逻辑外键）',
  `kb_id`      BIGINT UNSIGNED DEFAULT NULL COMMENT '评价参照的知识库（→knowledge_base.id，按库聚合被踩用）',
  `rating`     VARCHAR(16) NOT NULL COMMENT '评价：THUMBS_UP 赞 / THUMBS_DOWN 踩（§7.2 枚举字符串）',
  `reason`     VARCHAR(512) DEFAULT NULL COMMENT '踩的原因（仅 THUMBS_DOWN 时填，赞时 NULL）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_message_user` (`message_id`, `user_id`),     -- 同用户对同消息重复点 = 更新（upsert）
  KEY `idx_created_at` (`created_at`)                         -- §6.1 强制
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='回答点踩/点赞反馈（K0808 T7，覆盖写语义，无软删）';
