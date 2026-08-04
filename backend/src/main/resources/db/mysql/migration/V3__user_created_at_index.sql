-- V3：为 user 表补 §6.1 通用字段约定的标配索引 idx_created_at。
-- 原因：V2 建表时遗漏了 §6.1 模板里“所有表套用”的 `KEY idx_created_at (created_at)`，
--       导致脚本与规范/计划验收点不符。按 §9 纪律，已执行的 V2 不可改，新增本迁移补齐。
-- 合规依据：
--   §6.1 通用字段约定（所有表套用）模板第 255 行：`KEY idx_created_at (created_at)`
--   §6.2 单表索引 ≤5：补后 user 表仅 PK + uk_username + idx_created_at 共 3 个，达标。
ALTER TABLE `user` ADD KEY `idx_created_at` (`created_at`) COMMENT '创建时间索引（§6.1 标配）';
