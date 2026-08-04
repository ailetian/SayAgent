-- M1/T6 MySQL 基线迁移脚本（证明 Flyway 跑通）。
-- 说明：app_meta 是一张轻量键值元数据表，仅用于验证 Flyway 能按序号自动执行迁移；
--       真正的业务表（user / model_provider / agent / conversation 等）在后续 M2~M7 各 T*
--       任务里逐张按 §6.1 通用字段约定（id/created_at/updated_at/deleted）补迁移脚本。
-- 本脚本遵循 §9：所有 DDL 走 Flyway 迁移，禁手改库；§6.1：InnoDB + utf8mb4 + utf8mb4_0900_ai_ci。
CREATE TABLE IF NOT EXISTS app_meta (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `k`          VARCHAR(64) NOT NULL COMMENT '键',
  `v`          VARCHAR(255) DEFAULT NULL COMMENT '值',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_k` (`k`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='应用元数据基线表（验证 Flyway）';
