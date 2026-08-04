-- M3/T4 支撑迁移：model_provider 增加 model 列
-- 原因：T4 路由按 ProviderType 选主模型后，需把「具体模型名」带给 Client（§3.5 ProviderConfig.model），
--       而 T1 建表时未含该列。此处补齐，保持「代码 / 计划验收点 / DDL」三方一致。
-- 说明：§6.1 模板针对 CREATE TABLE；本操作为 ALTER，仅新增一列，遵循既有小写蛇形命名与小写约定。
ALTER TABLE `model_provider`
  ADD COLUMN `model` VARCHAR(64) DEFAULT NULL
  COMMENT '默认模型名（如 gpt-4o/claude-3-5-sonnet/gemini-1.5-pro/llama3），路由调用时传给 Client'
  AFTER `type`;
