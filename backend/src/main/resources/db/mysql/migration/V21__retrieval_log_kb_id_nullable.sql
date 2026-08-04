-- K5：retrieval_log.kb_id 改为可空。
-- 背景：§5.6 要求「每次问答记账」，含拒答场景。当 Agent 未挂载任何知识库时（refusal_reason=NO_KB），
-- 没有可用的 kb_id，但拒答日志仍须落库。原 DDL 将 kb_id 设为 NOT NULL，会导致 NO_KB 拒答无法记录。
-- 日志表本就不建外键（独立于业务实体生命周期），故仅放宽为可空，不动其他列/索引。
-- 配套：knowledge/entity/RetrievalLog.java 的 @Column(nullable = true)。

ALTER TABLE `retrieval_log`
    MODIFY COLUMN `kb_id` BIGINT UNSIGNED NULL COMMENT '知识库 id（NO_KB 拒答时为 NULL）';
