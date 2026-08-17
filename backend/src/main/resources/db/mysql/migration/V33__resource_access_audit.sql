-- M10/T6 资源授权审计日志（§7.11 重要操作留痕）
-- 追加写审计表：记录「谁 把 含XX(域)工具的 Agent 授给 谁」，事后可查可追责。
-- 设计：append-only，无 updated_at / deleted 列（与同包 resource_access 一致，不加软删注解）。
-- 索引：主键 + idx_created_at（§6.1）+ 资源维度查询 idx_resource，单表索引 ≤5 满足 §6.2。

CREATE TABLE `resource_access_audit` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `created_at`    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '操作时间（追加写）',
  `operator`      VARCHAR(64)  NOT NULL COMMENT '操作人（管理员登录名，来自 AuthContext）',
  `action`        VARCHAR(20)  NOT NULL COMMENT '操作类型：GRANT / REVOKE',
  `principal_type` VARCHAR(20) NOT NULL COMMENT '授权主体类型：ROLE / USER',
  `principal_id`   VARCHAR(64)  NOT NULL COMMENT '授权主体 id：角色名 / 登录名',
  `resource_type`  VARCHAR(20)  NOT NULL COMMENT '资源类型：KB / AGENT',
  `resource_id`    BIGINT       NOT NULL COMMENT '资源 id：knowledge_base.id / agent.id',
  `risk_summary`   VARCHAR(512) DEFAULT NULL COMMENT '被授权资源携带的敏感工具摘要（如 含财务·人事域工具3个）',
  `detail`        VARCHAR(512) DEFAULT NULL COMMENT '备注（如授权四权位摘要）',
  PRIMARY KEY (`id`),
  KEY `idx_created_at` (`created_at`),
  KEY `idx_resource` (`resource_type`, `resource_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='资源授权审计日志(M10/T6)';
