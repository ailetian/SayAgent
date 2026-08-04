-- K1：4 张新业务表（agent_kb_link / indexing_job / retrieval_log / eval_dataset）
-- 全部落 MySQL（业务关系，§6.1 跨库归属纪律「业务关系在 MySQL、向量在 pg」）；pg 只动索引/扩展（V3/V4）。
-- 模板遵循 §6.1（id BIGINT UNSIGNED / created_at / updated_at / deleted / KEY idx_created_at）
--           + §6.2（外键列建索引 / 联合索引含 deleted / 索引数 ≤5 含主键，写多读少表 ≤3）。

CREATE TABLE `agent_kb_link` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `agent_id`   BIGINT UNSIGNED NOT NULL                COMMENT 'Agent id（agent.id）',
  `kb_id`      BIGINT UNSIGNED NOT NULL                COMMENT '知识库 id（knowledge_base.id）',
  `created_by` VARCHAR(64)     NULL                    COMMENT '挂载操作人（username，仅审计）',
  `created_at` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted`    TINYINT(1)      NOT NULL DEFAULT 0       COMMENT '软删除标记 0未删 1已删',
  PRIMARY KEY (`id`),
  -- §6.2.3b：含 deleted 的查询把 deleted 纳入联合索引；唯一约束含 deleted，软删后可重建同一挂载
  UNIQUE KEY `uk_agent_kb` (`agent_id`, `kb_id`, `deleted`),
  KEY `idx_kb_id` (`kb_id`, `deleted`),
  KEY `idx_created_at` (`created_at`),
  CONSTRAINT `fk_akl_agent` FOREIGN KEY (`agent_id`) REFERENCES `agent` (`id`),
  CONSTRAINT `fk_akl_kb`    FOREIGN KEY (`kb_id`)    REFERENCES `knowledge_base` (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'Agent ↔ 知识库 多对多挂载（§3.5 核心）';

CREATE TABLE `indexing_job` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `doc_id`        BIGINT UNSIGNED NOT NULL                COMMENT '文档 id（document.id）',
  `kb_id`         BIGINT UNSIGNED NOT NULL                COMMENT '知识库 id（knowledge_base.id）',
  `batch_id`      VARCHAR(64)     NULL                    COMMENT '同批上传分组 id（批量重试用）',
  `stage`         VARCHAR(20)     NOT NULL DEFAULT 'UPLOAD' COMMENT '当前阶段 UPLOAD/PARSE/CHUNK/EMBED/STORE',
  `progress`      VARCHAR(20)     NULL                    COMMENT '进度 n/m（如 3/10）',
  `status`        VARCHAR(20)     NOT NULL DEFAULT 'QUEUED' COMMENT '任务状态 QUEUED/RUNNING/SUCCESS/FAILED',
  `fail_stage`    VARCHAR(20)     NULL                    COMMENT '失败所在阶段（精确标记死在哪环）',
  `error_code`    VARCHAR(50)     NULL                    COMMENT '细分错误码（加密PDF/扫描件无文本/格式损坏/embedding不可用/超时…）',
  `error_message` VARCHAR(1024)   NULL                    COMMENT '失败原因明细',
  `retry_count`   INT             NOT NULL DEFAULT 0       COMMENT '已重试次数（断点重试用）',
  `created_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted`       TINYINT(1)      NOT NULL DEFAULT 0       COMMENT '软删除标记 0未删 1已删',
  PRIMARY KEY (`id`),
  -- 写多读少：仅 3 个非主键索引（§6.2.4）。kb_id 高区分度在前，status 低区分度置中，deleted 纳入（§6.2.3b/6）。
  KEY `idx_kb_status` (`kb_id`, `status`, `deleted`),
  KEY `idx_doc_id` (`doc_id`, `deleted`),
  KEY `idx_batch_id` (`batch_id`, `deleted`),
  CONSTRAINT `fk_ij_kb`  FOREIGN KEY (`kb_id`)  REFERENCES `knowledge_base` (`id`),
  CONSTRAINT `fk_ij_doc` FOREIGN KEY (`doc_id`) REFERENCES `document` (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '异步索引任务（逐节点进度 + 断点重试）';

CREATE TABLE `retrieval_log` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `kb_id`          BIGINT UNSIGNED NOT NULL                COMMENT '知识库 id',
  `agent_id`       BIGINT UNSIGNED NOT NULL                COMMENT 'Agent id',
  `query`          VARCHAR(1000)   NOT NULL                COMMENT '用户原始提问',
  `rewritten`      VARCHAR(1000)   NULL                    COMMENT 'Query Rewriting 后的查询（R4）',
  `hit_chunks`     TEXT            NULL                    COMMENT '命中片段（json 数组，溯源 R6）',
  `scores`         TEXT            NULL                    COMMENT '各片段得分（json 数组）',
  `answer`         MEDIUMTEXT      NULL                    COMMENT '最终回答（用于复盘）',
  `cost_ms`        BIGINT          NULL                    COMMENT '检索耗时(ms)',
  `rejected`       TINYINT(1)      NULL                    COMMENT '是否拒答（R3）',
  `refusal_reason` VARCHAR(20)     NULL                    COMMENT '拒答原因 NO_KB/NO_HIT/BELOW_THRESHOLD',
  `top_score`      DECIMAL(6,4)    NULL                    COMMENT '最高得分（拒答线对比用）',
  `threshold`      DECIMAL(6,4)    NULL                    COMMENT '本次生效的拒答阈值',
  `top_candidates` TEXT            NULL                    COMMENT '候选片段（json，拒答分析用）',
  `created_at`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted`        TINYINT(1)      NOT NULL DEFAULT 0       COMMENT '软删除标记 0未删 1已删',
  PRIMARY KEY (`id`),
  -- 写多读少（每次检索一条）：3 个非主键索引；kv/agent 含 deleted，created_at 单列（§6.1）。
  -- 不建外键：日志应存活于知识库 / Agent 软删之后，独立于业务实体生命周期。
  KEY `idx_kb_id` (`kb_id`, `deleted`),
  KEY `idx_agent_id` (`agent_id`, `deleted`),
  KEY `idx_created_at` (`created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '检索日志 + 拒答分析（§5.6）';

CREATE TABLE `eval_dataset` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `kb_id`         BIGINT UNSIGNED NOT NULL                COMMENT '知识库 id',
  `question`      VARCHAR(1000)   NOT NULL                COMMENT '评测问题',
  `type`          VARCHAR(20)     NULL                    COMMENT '题型（事实/推理/拒答…）',
  `keywords`      VARCHAR(255)    NULL                    COMMENT '关键词（命中校验用）',
  `expected`      MEDIUMTEXT      NULL                    COMMENT '期望答案',
  `should_reject` TINYINT(1)      NULL                    COMMENT '是否应拒答（门禁用）',
  `created_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted`       TINYINT(1)      NOT NULL DEFAULT 0       COMMENT '软删除标记 0未删 1已删',
  PRIMARY KEY (`id`),
  KEY `idx_kb_id` (`kb_id`, `deleted`),
  KEY `idx_created_at` (`created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '评测集（题集打分门禁，§3.8/§9）';
