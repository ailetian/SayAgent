-- M5 T1：Pg 向量库 schema（替代原 @PostConstruct 运行时建表，满足 CLAUDE.md §9 Flyway 纪律）
-- 对应审核报告 P0-1 / P0-2：表结构必须走 Flyway；向量列必须显式建 HNSW 索引（§6.6/§6.7）
-- 参考：06_M5 全局验收「HNSW 索引 USING hnsw (embedding vector_cosine_ops)」

-- 启用 pgvector 扩展（一次即可，幂等）
CREATE EXTENSION IF NOT EXISTS vector;

-- 切片向量表
CREATE TABLE IF NOT EXISTS document_chunk (
    id          BIGSERIAL     PRIMARY KEY,
    document_id VARCHAR(64)   NOT NULL,
    kb_id       BIGINT        NOT NULL,
    seq         INT           NOT NULL,
    content     TEXT          NOT NULL,
    embedding   vector(1024)  NOT NULL,
    created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- B-Tree 过滤索引（按 kb / document 定位）
CREATE INDEX IF NOT EXISTS idx_chunk_kb  ON document_chunk (kb_id);
CREATE INDEX IF NOT EXISTS idx_chunk_doc ON document_chunk (document_id);

-- §6.1：每张表要有 created_at 索引
CREATE INDEX IF NOT EXISTS idx_chunk_created_at ON document_chunk (created_at);

-- §6.6 / §6.7：向量列必须建 HNSW 索引，否则检索退化为全表扫描（P0 阻断项）
CREATE INDEX IF NOT EXISTS idx_document_chunk_embedding
    ON document_chunk USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
