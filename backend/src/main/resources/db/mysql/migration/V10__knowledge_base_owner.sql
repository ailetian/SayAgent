-- M5 整改（T1/T5 验收）：知识库按人（owner）隔离，防越权访问他人知识库
-- 数据隔离维度保留 kb_id（M5 实际落地）；owner_id 绑定创建者登录名（username），
-- 由 KnowledgeService 在首个上传者时惰性绑定，检索/上传时校验归属。
-- 索引遵循 AGENTS.md §6.1 单表索引数约束（knowledge_base 现有 idx_kb_xxx 之外追加 idx_owner_id）。

ALTER TABLE `knowledge_base`
    ADD COLUMN `owner_id` VARCHAR(64) DEFAULT NULL COMMENT '归属人登录名(username)，按人隔离防越权',
    ADD KEY `idx_owner_id` (`owner_id`);
