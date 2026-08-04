-- M5 整改扩展：owner_id 改名为 creator_id（语义收敛）
-- 原 owner_id 表达「私有主人」，与 RBAC（管理员按角色/人分配）冲突；
-- 改名后仅记录创建者(审计用)，访问权统一由 kb_access 表管理。
ALTER TABLE `knowledge_base`
    DROP KEY `idx_owner_id`,
    CHANGE COLUMN `owner_id` `creator_id` VARCHAR(64) DEFAULT NULL
        COMMENT '创建者登录名(username)，仅审计用；访问权统一由 kb_access 表管理',
    ADD KEY `idx_creator_id` (`creator_id`);
