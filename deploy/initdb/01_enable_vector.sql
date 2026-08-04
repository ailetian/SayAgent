-- 启用 pgvector 扩展（M5 知识库向量检索需要）。
-- pgvector/pgvector 镜像首次启动会执行 /docker-entrypoint-initdb.d 下的 SQL，
-- 自动 CREATE EXTENSION，之后 application.yml 里的 PostgreSQL 数据源才能建向量表。
CREATE EXTENSION IF NOT EXISTS vector;
