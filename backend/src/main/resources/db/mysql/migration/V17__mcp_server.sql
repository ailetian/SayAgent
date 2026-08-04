CREATE TABLE `mcp_server` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name`        VARCHAR(64)  NOT NULL COMMENT 'MCP Server 名称',
  `address`     VARCHAR(255) NOT NULL COMMENT 'MCP Server 地址',
  `type`        VARCHAR(20)  NOT NULL DEFAULT 'STDIO' COMMENT 'STDIO/SSE/HTTP',
  `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  `created_at`  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted`     TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '软删除标记 0未删 1已删',
  PRIMARY KEY (`id`),
  KEY `idx_status` (`status`, `deleted`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='MCP Server 配置表';
