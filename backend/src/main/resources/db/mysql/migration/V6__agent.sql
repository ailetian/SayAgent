-- M4/T1 Agent 配置表（§6.1 模板 + §6.2 索引规则）
-- 字段顺序：主键 → 业务字段 → created_at/updated_at/deleted（固定收尾，BaseEntity 软删）
-- 索引：所有表强制 idx_created_at；外键列 model_provider_id 建联合 (model_provider_id, deleted)；
--       启用/排序/默认 Agent 均为高频过滤+排序，按 §6.2 规则3b 把 deleted 纳入联合索引；
--       单表索引(含主键)共 5 个，未超 §6.2 规则4 上限。
-- 注：V5 已被 M3 的 add_model_to_model_provider 占用，故本表顺延为 V6（现实优先于计划文档）。
CREATE TABLE `agent` (
  `id`                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name`                VARCHAR(50)   NOT NULL COMMENT 'Agent 名称',
  `description`         VARCHAR(500)  NOT NULL DEFAULT '' COMMENT '描述（一句话说清这个 Agent 干嘛的）',
  `system_prompt`       TEXT          NOT NULL COMMENT 'System Prompt（人设/指令，不可为空）',
  `model_provider_id`   BIGINT UNSIGNED NOT NULL COMMENT '默认模型厂商 id（来自模型管理 model_provider.id，类型须与 model_provider.id 的 BIGINT UNSIGNED 一致以满足外键）',
  `model`               VARCHAR(100)  NOT NULL COMMENT '该 Agent 实际使用的模型名（如 gpt-4o）',
  `secret`              VARCHAR(500)  NOT NULL DEFAULT '' COMMENT '调用外部 Agent 的秘钥；不为空则前端传入，绝对不可返回前端',
  `user_password`       VARCHAR(500)  NOT NULL DEFAULT '' COMMENT '外部 Agent 的用户密码；同上不返回前端',
  `enabled`             TINYINT(1)    NOT NULL DEFAULT 1 COMMENT '是否启用 1启用 0停用',
  `is_default_agent`    TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '是否默认 Agent 1默认 0否',
  `sort_order`          INT           NOT NULL DEFAULT 0 COMMENT '排序权重，越小越靠前',
  `temperature`         DECIMAL(3,2)  NOT NULL DEFAULT 0.70 COMMENT '采样温度 0~1',
  `top_p`               DECIMAL(3,2)  NOT NULL DEFAULT 1.00 COMMENT '核采样概率 0~1',
  `max_tokens`          INT           NOT NULL DEFAULT 2048 COMMENT '单次请求最大生成 token 数',
  `max_context_tokens`  INT           NOT NULL DEFAULT 8192 COMMENT '上下文窗口 token 上限',
  `created_at`          DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`          DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted`             TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '软删除标记 0未删 1已删',
  PRIMARY KEY (`id`),
  KEY `idx_model_provider_id` (`model_provider_id`, `deleted`),  -- §6.2 外键列建索引 + deleted（规则3b）
  KEY `idx_enabled` (`enabled`, `deleted`),                      -- 启用过滤 + deleted
  KEY `idx_sort_order` (`sort_order`, `deleted`),                -- 排序权重 + deleted
  KEY `idx_created_at` (`created_at`),                           -- §6.1 模板强制：所有表套用
  CONSTRAINT `fk_agent_model_provider_id` FOREIGN KEY (`model_provider_id`) REFERENCES `model_provider` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Agent 配置';
