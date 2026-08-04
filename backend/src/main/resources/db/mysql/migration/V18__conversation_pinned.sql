-- M6 补充：会话「置顶」能力（配合后端 PUT /api/chat/{id} 重命名、PUT /api/chat/{id}/pin 置顶）
-- 说明：新增 pinned 标志列；列表按 pinned desc + last_active_at desc 排序（置顶会话排最前）。
-- §6.1 通用字段约定已满足；索引遵循 §6.2（查询列建索引，单表索引含主键 ≤ 5）。

ALTER TABLE `conversation`
  ADD COLUMN `pinned` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否置顶 0否 1是' AFTER `status`;

-- 置顶列表查询覆盖索引：(user_id, deleted, pinned, last_active_at)
CREATE INDEX `idx_user_pinned_active` ON `conversation` (`user_id`, `deleted`, `pinned`, `last_active_at`);
