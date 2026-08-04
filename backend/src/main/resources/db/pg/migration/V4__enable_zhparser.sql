-- K1 / §5.2 / P6（§10）(方案 A，2026-07-27 拍板)：容错启用中文分词 zhparser。
-- 当前官方 pgvector 镜像（pgvector/pgvector:pg16）不含 zhparser，直接 CREATE EXTENSION 必失败
-- （IF NOT EXISTS 只防「已装重复装」，防不了「根本没有」）。
-- 故用 DO $$ ... EXCEPTION 块：能装则装（建 zhparser_cfg 配置 + n/v/a/i/e/l 映射 simple），
-- 装不上自动降级 PG 自带 simple 分词，绝不阻断 Flyway / 应用启动。
-- K2 的 zhparser 探针负责运行时检测（探测到未装 → to_tsvector 用 simple），两者正好配套。
-- 后期若要完整中文分词，另起任务自建含 zhparser 的镜像，非本任务交付物。
DO $$
BEGIN
  CREATE EXTENSION IF NOT EXISTS zhparser;
  CREATE TEXT SEARCH CONFIGURATION zhparser_cfg (PARSER = zhparser);
  ALTER TEXT SEARCH CONFIGURATION zhparser_cfg ADD MAPPING FOR n, v, a, i, e, l WITH simple;
EXCEPTION WHEN OTHERS THEN
  RAISE NOTICE 'zhparser unavailable, fallback to simple: %', SQLERRM;
END $$;
