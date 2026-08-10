-- K0808 T11：message 表补 model 列（记录实际模型名，与 conversation_log.model 同源，供回答透传展示，§4.9）。
-- 对应 T11 验收点：问答响应 JSON 含 provider/model；user 消息留空。
-- 说明：message 为 RANGE 分区表，ADD COLUMN 会落到各分区，无需重建分区。
-- 版本顺延：紧接 T10 的 V29，故本迁移为 V30（message 表此前仅有 V16 的 provider/tokens）。

ALTER TABLE `message`
  ADD COLUMN `model` VARCHAR(64) NULL COMMENT '实际模型名(如 gpt-4o/qwen-max)，user 消息留空' AFTER `provider`;
