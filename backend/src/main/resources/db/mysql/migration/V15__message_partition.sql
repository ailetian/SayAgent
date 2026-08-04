-- M6 T1：按 §6.3 给 message 表加 RANGE 分区 + 复合主键 (id, created_at)。
-- 对应计划验收点 3 / 5 / 8：PRIMARY KEY(id, created_at) + PARTITION BY RANGE(TO_DAYS(created_at))
--                       + 复合 @IdClass(id, created_at)（见 Message.java）。
-- 保留 T2 实际需要的列（seq / status / user_id）与历史回放索引（idx_conversation_seq）；
-- 去掉与计划不符且 T2 不用的 idx_user / idx_created_at（created_at 已由分区主键覆盖，§6.3 不再另建）。
-- 注意：MySQL 不允许在一条 ALTER 里同时做 DROP/ADD 索引与 PARTITION BY，故拆成多条语句。
--       分区键 created_at 必须包含在每个唯一键中；本表除 PK 外无其它唯一键，故改 PK 后即可分区。

-- 1) 复合主键：先删旧 PK（仅 id），再加 (id, created_at)。
ALTER TABLE `message`
  DROP PRIMARY KEY,
  ADD PRIMARY KEY (`id`, `created_at`);

-- 2) 索引整理：去掉 T2 不用的 idx_user / idx_created_at，补 §6.3 要求的 idx_conv_created。
ALTER TABLE `message`
  DROP INDEX `idx_user`,
  DROP INDEX `idx_created_at`,
  ADD INDEX `idx_conv_created` (`conversation_id`, `deleted`, `created_at`);

-- 3) 分区：按 created_at 做 RANGE 分区（2026 年一个区，其余进 pmax）。
ALTER TABLE `message`
  PARTITION BY RANGE (TO_DAYS(`created_at`)) (
    PARTITION p2026 VALUES LESS THAN (TO_DAYS('2027-01-01')),
    PARTITION pmax VALUES LESS THAN MAXVALUE
  );
