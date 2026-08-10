-- M8/T4 改造：skill 由「工具型」改为「提示词型」（V26 已在 9095/真实库 applied，按 §9 不得改已落地迁移）
-- 去工具列（type/definition/config），加 prompt_text/description；清掉 V26 自动种子的工具型行，让库初始为空。

-- 先清掉 V26 同步进来的工具型行（current-time 等）：新模型库初始为空、按需写提示词
DELETE FROM `skill`;

ALTER TABLE `skill`
    DROP COLUMN `type`,
    DROP COLUMN `definition`,
    DROP COLUMN `config`,
    ADD COLUMN `description` VARCHAR(255) NULL,
    ADD COLUMN `prompt_text` TEXT NOT NULL;
