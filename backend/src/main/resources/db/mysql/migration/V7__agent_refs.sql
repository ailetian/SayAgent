-- V7：为 agent 表补充「知识库引用 / 工具引用」两个 JSON 数组字段（M4/T3）
-- 约定：引用 id 列表以文本形式存为 JSON 数组（如 [1,2]），由 RefsJsonConverter 读写转换。
-- 二者不是外键、也不是高频过滤列，按 §6.2 不额外建索引。

ALTER TABLE agent
    ADD COLUMN knowledge_refs TEXT NULL COMMENT '知识库引用 id 列表（JSON 数组，如 [1,2]），缺省为空数组',
    ADD COLUMN tool_refs       TEXT NULL COMMENT '工具引用 id 列表（JSON 数组，如 [3,4]），缺省为空数组';
