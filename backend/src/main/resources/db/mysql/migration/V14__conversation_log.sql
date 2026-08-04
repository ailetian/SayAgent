-- M6 T1：对话日志表（仅 MySQL）。
-- 模板严格遵循 AGENTS.md §6.1 通用字段（id/created_at/updated_at/deleted）
--           与 §6.2 索引纪律（外键列建索引；含 deleted 查询把 deleted 纳入联合索引 规则3b；
--           所有表强制 idx_created_at；单表索引含主键 ≤ 5 个，写多读少 ≤ 3 个 规则4）。
-- 说明：
--   1) 本表为「写多读少」流水账（§6.2 规则4 → 索引 ≤ 3 个），故仅保留 PK + idx_created_at + idx_user_id。
--   2) conversation_id / agent_id 为「逻辑外键」，类型对齐已落库的 conversation 表
--      （VARCHAR(50) 业务键，非计划范例里的 BIGINT 内部 FK），保证引用一致。
--   3) question 为 MEDIUMTEXT，不建索引（§6.2 规则7，语义检索交 pgvector）。

CREATE TABLE `conversation_log` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `created_at`      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted`         TINYINT(1) NOT NULL DEFAULT 0 COMMENT '软删除标记 0未删 1已删',
  `user_id`         BIGINT UNSIGNED NOT NULL COMMENT '谁问的（外键→user.id）',
  `agent_id`        VARCHAR(50) DEFAULT NULL COMMENT '用的哪个 Agent（→conversation.agent_id 逻辑外键）',
  `conversation_id` VARCHAR(50) DEFAULT NULL COMMENT '所属会话（→conversation.conversation_id 逻辑外键）',
  `question`        MEDIUMTEXT NOT NULL COMMENT '用户问题（不索引，§6.2 规则7）',
  `in_tok`          INT DEFAULT 0 COMMENT '输入 token（§4.9）',
  `out_tok`         INT DEFAULT 0 COMMENT '输出 token（§4.9）',
  `provider`        VARCHAR(32) DEFAULT NULL COMMENT '实际命中厂商（§4.9）',
  `model`           VARCHAR(64) DEFAULT NULL COMMENT '实际模型（§4.9）',
  `fallback`        TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否走降级（§4.9）',
  PRIMARY KEY (`id`),
  KEY `idx_created_at` (`created_at`),                      -- §6.1 强制
  KEY `idx_user_id` (`user_id`, `deleted`)                  -- §6.2 外键列必建索引 + 含 deleted（规则3b）
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='对话日志（M6 模块6，写多读少流水账）';
