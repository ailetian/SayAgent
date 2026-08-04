-- M6 T1：对话/消息业务表（仅 MySQL；document_chunk 走 pg 向量库，不在此文件）
-- 模板严格遵循 AGENTS.md §6.1 通用字段（id/created_at/updated_at/deleted）
--           与 §6.2 索引纪律（外键列必建索引；含 deleted 查询把 deleted 纳入联合索引 规则3b；
--           所有表强制 idx_created_at；单表索引含主键 ≤ 5 个 规则4）。
-- 说明：conversation_id 这类「对外暴露的业务主键」采用 (business_id, deleted) 联合唯一键，
--       软删后旧 id 可被新会话复用（与 V8 document 同款坑位处理）。

CREATE TABLE `conversation` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `created_at`      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted`         TINYINT(1) NOT NULL DEFAULT 0 COMMENT '软删除标记 0未删 1已删',
  `conversation_id` VARCHAR(50) NOT NULL COMMENT '客户端生成的可选 UUID，业务主键',
  `user_id`         BIGINT UNSIGNED NOT NULL COMMENT '所属用户 id（外键→user.id）',
  `title`           VARCHAR(200) NOT NULL DEFAULT '' COMMENT '会话标题（首条消息前 20 字）',
  `agent_id`        VARCHAR(50) DEFAULT NULL COMMENT '绑定的 Agent id（可选）',
  `message_count`   BIGINT NOT NULL DEFAULT 0 COMMENT '消息条数（乐观计数）',
  `last_active_at`  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后活跃时间',
  `status`          TINYINT NOT NULL DEFAULT 0 COMMENT '会话状态 0=ACTIVE 1=ARCHIVED',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_conversation_id` (`conversation_id`, `deleted`),
  KEY `idx_user_last_active` (`user_id`, `deleted`, `last_active_at`),
  KEY `idx_agent_id` (`agent_id`, `deleted`),
  KEY `idx_created_at` (`created_at`),
  CONSTRAINT `fk_conversation_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='对话会话（M6）';

CREATE TABLE `message` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `created_at`      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted`         TINYINT(1) NOT NULL DEFAULT 0 COMMENT '软删除标记 0未删 1已删',
  `conversation_id` VARCHAR(50) NOT NULL COMMENT '所属会话 id（→conversation.conversation_id，逻辑外键）',
  `user_id`         BIGINT UNSIGNED NOT NULL COMMENT '消息归属用户 id（→user.id，冗余便于按用户检索）',
  `role`            VARCHAR(10) NOT NULL COMMENT '角色 USER/ASSISTANT/SYSTEM',
  `content`         MEDIUMTEXT NOT NULL COMMENT '消息正文（MEDIUMTEXT 兼容长文/超长回显）',
  `seq`             INT NOT NULL DEFAULT 0 COMMENT '会话内序号（从 1 自增，用于排序与幂等）',
  `status`          VARCHAR(10) NOT NULL COMMENT '状态 SENT/PENDING/FAILED',
  PRIMARY KEY (`id`),
  KEY `idx_conversation_seq` (`conversation_id`, `deleted`, `seq`),
  KEY `idx_user` (`user_id`, `deleted`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会话消息（M6）';
