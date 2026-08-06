-- M6 T3：message 表增加调用轨迹列（对话日志铁律：KB 检索/MCP 工具调用记录必须持久化、可事后回看）
-- 存 JSON 数组：[{kind:'retrieval'|'tool', label, status, docId?, score?, toolName?, args?, result?, success?}]
-- 仅 MySQL（document_chunk 向量明细在 PG，不在此文件）。nullable，不建索引（§6.2 规则7：长文本不索引）。

ALTER TABLE `message`
    ADD COLUMN `trace_json` MEDIUMTEXT NULL COMMENT '调用轨迹 JSON（知识库检索命中 / MCP 工具调用明细），供事后回看';
