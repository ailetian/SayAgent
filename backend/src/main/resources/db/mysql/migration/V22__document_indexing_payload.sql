-- K6 异步索引流水线：document 表追加断点续跑/重传所需的暂存列
-- 对应 K6 验收点：断点重试（EMBED 失败不重解析切分）、P5 重传删旧前定位、checksum 去重定位。
-- 说明：pg 的 document_chunk.embedding 是 NOT NULL，无法暂存"无向量的半套 chunk"，
-- 故断点状态（解析文本 + 切片文本）落在 MySQL 的 document 上（同源事务、强一致）。

-- 上传提供的原始文本（TEXT/URL/内容型上传）：PARSE 阶段读取，断点续跑时免重解析
ALTER TABLE `document`
    ADD COLUMN `raw_content` TEXT NULL COMMENT '上传提供的原始文本（TEXT/URL/内容型），索引流水线 PARSE 阶段读取，断点续跑用';

-- 断点续跑暂存：切片文本 JSON 数组（["块1","块2",...]）。
-- EMBED/STORE 失败续跑时直接读它免重切；成功后清空（INDEXING_JOB 不再需要，释放空间）
ALTER TABLE `document`
    ADD COLUMN `index_payload` TEXT NULL COMMENT '断点续跑暂存：切片文本 JSON 数组，EMBED/STORE 失败续跑免重切，成功后清空';
