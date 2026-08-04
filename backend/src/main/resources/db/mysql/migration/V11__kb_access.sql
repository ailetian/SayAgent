-- M5 整改扩展（RBAC）：知识库访问授权表
-- 一条记录 = 某知识库(kb_id) 对 某角色/某人(target) 开放访问权
-- 模板严格遵循 CLAUDE.md §6.1（id BIGINT UNSIGNED、四字段、KEY idx_created_at）/§6.2（索引纪律）
CREATE TABLE `kb_access` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `kb_id`       BIGINT UNSIGNED NOT NULL COMMENT '知识库 id（knowledge_base.id）',
  `target_type` VARCHAR(20)     NOT NULL                  COMMENT '授权目标类型 ROLE/USER',
  `target_id`   VARCHAR(64)     NOT NULL                  COMMENT '授权目标：ROLE=角色名，USER=登录名',
  `created_at`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted`     TINYINT(1)      NOT NULL DEFAULT 0        COMMENT '软删除标记 0未删 1已删',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_kb_target` (`kb_id`, `target_type`, `target_id`, `deleted`),
  KEY `idx_kb_id` (`kb_id`),
  KEY `idx_created_at` (`created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '知识库访问授权(RBAC)';
