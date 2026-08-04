-- M5 T3 运维可观测：INDEXING→FAILED 时回填失败原因，便于排查（替代原仅靠 log.error）。
-- 对应审核报告 P1-4：原 document 表缺 error_message 列，失败原因无法持久化。
ALTER TABLE `document` ADD COLUMN `error_message` VARCHAR(1024) NULL COMMENT '索引失败原因';
