-- K7：为 agent 表补 created_by 列（挂载权限判定，§3.5）。
-- 仅创建者/admin 可改某 Agent 的知识库挂载（agent_kb_link），
-- 需记录创建者登录名；原 V6 建表时遗漏，本迁移补齐（与 agent_kb_link.created_by 审计列呼应）。

ALTER TABLE `agent`
    ADD COLUMN `created_by` VARCHAR(64) NULL COMMENT 'Agent 创建者登录名（挂载权限判定，§3.5）';
