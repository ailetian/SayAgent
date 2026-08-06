-- K8 修复：知识库直问（/knowledge/{kbId}/ask）没有 Agent 上下文，RetrievalLog.agent_id 必须可空，
-- 否则 KbQaService.ask 传 agentId=null 时插入报「Column 'agent_id' cannot be null」→ 5000。
-- 与 kb_id 对称（§5.6 每次问答都记账，无论有没有 Agent）。索引 idx_agent_id 兼容 NULL，无需改动。
ALTER TABLE `retrieval_log` MODIFY COLUMN `agent_id` BIGINT UNSIGNED NULL COMMENT 'Agent id（知识库直问时为 NULL）';
