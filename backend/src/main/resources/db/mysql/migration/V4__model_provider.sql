-- M3/T1 模型提供商表（§6.1 模板 + §6.2 索引规则）
-- 字段顺序：主键 → 业务字段 → created_at/updated_at/deleted（固定收尾，BaseEntity 软删）
-- 索引：所有表强制 idx_created_at；枚举列 type 区分度低，放联合索引 (type, deleted)
CREATE TABLE `model_provider` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name`        VARCHAR(64)  NOT NULL COMMENT '展示名（如 "公司OpenAI"）',
  `api_url`     VARCHAR(255) NOT NULL COMMENT '接口地址（如 https://api.openai.com/v1）',
  `secret`      VARCHAR(255) DEFAULT NULL COMMENT '秘钥：仅后端读，绝不返前端（§7.11 脱敏在 T5）',
  `type`        VARCHAR(20)  NOT NULL COMMENT '厂商类型 OPENAI/CLAUDE/GEMINI/OLLAMA',
  `enabled`     TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用 1启用 0停用',
  `is_default`  TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否默认模型 1默认 0否',
  `sort_order`  INT          NOT NULL DEFAULT 0 COMMENT '排序权重，越小越靠前',
  `created_at`  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted`     TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '软删除标记 0未删 1已删',
  PRIMARY KEY (`id`),
  KEY `idx_type` (`type`, `deleted`),     -- §6.2 枚举列区分度低，联合 (type, deleted)
  KEY `idx_created_at` (`created_at`)     -- §6.1 模板强制：所有表套用
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='模型提供商配置';
