-- K1 / §6.6：移除误建的 HNSW 向量索引，首版全表精确扫描更准。
-- 与 2026-07-27 暂缓决策一致：内部 20~50 人、单库 chunk 数远 < 1 万，精确扫描已毫秒级；
-- HNSW 是近似索引、本质拿召回率换速度，过早建反降「准」（小库更应「宁可慢一点也要准」）。
-- 升级路径（触发条件：单库 chunk 达数万级且 P95 不可接受）再建 HNSW / ivfflat，非本任务范围。
-- 注意：PostgreSQL 的 DROP INDEX 语法不含 `ON <表>` 子句（那是 MySQL/SQL Server 写法），直接写索引名即可。
-- 索引由 V2 建在默认 schema（public），同 schema 内索引名唯一，故无需表名/模式限定。
DROP INDEX IF EXISTS idx_document_chunk_embedding;
