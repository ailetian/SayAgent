-- M8/T4 技能模块落库（§9 Flyway 顺延：上一版 V25=message_trace，本任务 V26）
-- 建 skill 表 + agent 加 skill_refs 列（§6.1 DDL 模板：四字段 + idx_created_at）

CREATE TABLE `skill` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT,
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`    TINYINT(1)   NOT NULL DEFAULT 0,
    `name`       VARCHAR(100) NOT NULL,
    `type`       VARCHAR(20)  NOT NULL,
    `definition` TEXT         NULL,
    `config`     TEXT         NULL,
    `enabled`    TINYINT(1)   NOT NULL DEFAULT 1,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`),
    KEY `idx_created_at` (`created_at`),
    KEY `idx_enabled` (`enabled`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Agent 表加「挂载了哪些技能」列（仿 tool_refs，存 JSON 数组文本 '[1,2]'）
ALTER TABLE `agent` ADD COLUMN `skill_refs` TEXT NULL;
