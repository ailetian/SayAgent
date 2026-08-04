-- M5 T1：知识库与文档业务表（仅 MySQL；document_chunk 走 pg 向量库，不在此文件）
-- 模板严格遵循 AGENTS.md §6.1 / §6.2（id BIGINT UNSIGNED、created_at/updated_at、deleted、KEY idx_created_at、外键列必建索引）

CREATE TABLE `knowledge_base` (
  `id`                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name`                VARCHAR(80)    NOT NULL                  COMMENT '知识库名称',
  `description`         VARCHAR(500)   NOT NULL DEFAULT ''       COMMENT '描述',
  `embedding_model`     VARCHAR(100)   DEFAULT NULL              COMMENT 'embedding 模型名',
  `embedding_dim`       INT            NOT NULL DEFAULT 1024     COMMENT '向量维度',
  `similarity_threshold` DECIMAL(4,3)  NOT NULL DEFAULT 0.600    COMMENT '检索相似度阈值',
  `created_at`          DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`          DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted`             TINYINT(1)     NOT NULL DEFAULT 0        COMMENT '软删除标记 0未删 1已删',
  PRIMARY KEY (`id`),
  KEY `idx_created_at` (`created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '知识库';

CREATE TABLE `document` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `document_id`   VARCHAR(64)     NOT NULL                  COMMENT '业务文档 id（UUID，对外暴露）',
  `kb_id`         BIGINT UNSIGNED NOT NULL                  COMMENT '所属知识库 id（knowledge_base.id）',
  `title`         VARCHAR(255)    NOT NULL                  COMMENT '文档标题',
  `source_type`   VARCHAR(20)     NOT NULL                  COMMENT '来源类型 FILE/URL/TEXT',
  `source_ref`    VARCHAR(512)    DEFAULT NULL              COMMENT '来源引用（文件路径/URL）',
  `status`        VARCHAR(20)     NOT NULL                  COMMENT '索引状态 UPLOADED/INDEXING/INDEXED/FAILED',
  `chunk_count`   INT             NOT NULL DEFAULT 0        COMMENT '切片数',
  `created_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted`       TINYINT(1)      NOT NULL DEFAULT 0        COMMENT '软删除标记 0未删 1已删',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_document_id` (`document_id`, `deleted`),
  KEY `idx_kb_id` (`kb_id`, `deleted`),
  KEY `idx_created_at` (`created_at`),
  CONSTRAINT `fk_document_kb` FOREIGN KEY (`kb_id`) REFERENCES `knowledge_base` (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '知识库文档';
