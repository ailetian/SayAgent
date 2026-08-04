-- M6 T3：message 表补 provider / tokens 两列（T3 落库 user/assistant 消息需记录命中厂商与 token 用量，§4.9）。
-- 对应 T3 验收点1：写 user/assistant 消息（role/content/conversation_id/user_id/seq/status/provider/tokens）。
-- 说明：message 为 RANGE 分区表，ADD COLUMN 会落到各分区，无需重建分区。

ALTER TABLE `message`
  ADD COLUMN `provider` VARCHAR(32) NULL COMMENT '命中厂商(OPENAI/OLLAMA)，user 消息留空' AFTER `status`,
  ADD COLUMN `tokens`   INT          NULL COMMENT '输出/输入 token 估算，用于 §4.9 统计'        AFTER `provider`;
