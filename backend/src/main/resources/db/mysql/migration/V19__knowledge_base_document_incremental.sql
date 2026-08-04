-- K1：KB / Document 增量字段（下一顺位迁移，不覆盖已有 V1~V18）
-- 对应 AGENTS.md §6.1（四字段已存在，此处仅增量）/ §8.1（KnowledgeBase 增量）/ §8.2（Document 增量）
-- 所有 DDL 走 Flyway（§9），禁止手改表 / 手动 ALTER（本脚本本身就是 Flyway 迁移）。

-- knowledge_base 增量字段
ALTER TABLE `knowledge_base`
  ADD COLUMN `rag_config`     JSON          NULL                  COMMENT '库级 RAG 参数（JSON，参数会增减，免改表；K2 解析为 RagConfig）',
  ADD COLUMN `chunk_strategy` VARCHAR(20)    NULL DEFAULT 'AUTO'  COMMENT '切片策略 AUTO/RECURSIVE/MARKDOWN_HEADER',
  ADD COLUMN `language`       VARCHAR(20)    NULL DEFAULT 'zh-CN' COMMENT '文档语言，影响分词',
  ADD COLUMN `token_count`    BIGINT         NULL DEFAULT 0       COMMENT '库内 token 总量（成本/配额可见）',
  ADD COLUMN `status`         VARCHAR(20)    NOT NULL DEFAULT 'ACTIVE' COMMENT '库状态 ACTIVE/ARCHIVED（删除由 deleted 软删覆盖）',
  ADD COLUMN `is_public`      TINYINT(1)     NOT NULL DEFAULT 1   COMMENT '可否被挂载到 Agent（§3.5 预留，默认 true）';

-- document 增量字段
ALTER TABLE `document`
  ADD COLUMN `checksum`    VARCHAR(64)  NULL                  COMMENT 'R8 去重，防重复向量',
  ADD COLUMN `size_bytes`  BIGINT       NULL DEFAULT 0        COMMENT '文件大小（列表展示 + 20MB 校验）',
  ADD COLUMN `mime_type`   VARCHAR(100) NULL                  COMMENT '文件 MIME（白名单校验记录）',
  ADD COLUMN `token_count` INT          NULL DEFAULT 0        COMMENT '单文档 token 成本';
