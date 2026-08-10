-- K0808 T10：message 表补 tokens_in 列（输入 token 用量落库，与 conversation_log.in_tok 对账，§4.9）。
-- 对应 T10 验收点1：message 表含 tokens_in 列；老数据该列为 NULL，前端兼容显示「—」。
-- 说明：message 为 RANGE 分区表，ADD COLUMN 会落到各分区，无需重建分区。
-- 版本顺延：当前 mysql 实际最大迁移为 V28（message_feedback），故本迁移为 V29。

ALTER TABLE `message`
  ADD COLUMN `tokens_in` BIGINT NULL COMMENT '输入 token 用量，与 conversation_log.in_tok 对账；老数据为 null' AFTER `tokens`;
