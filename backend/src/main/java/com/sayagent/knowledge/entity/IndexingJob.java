package com.sayagent.knowledge.entity;

import com.sayagent.common.base.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

/**
 * 异步索引任务（K1/K6）。
 *
 * <p>大白话：每上传一个文件就建一条索引任务，记录它现在跑到哪一步（解析/切片/向量化/入库）、
 * 成功还是失败、失败死在哪一步、重试过几次。靠它做「逐节点进度 + 断点重试 + 批量重试」。
 * 软删除同其它业务表（§6.1）。
 */
@Entity
@Table(name = "indexing_job")
@SQLRestriction("deleted = 0")
@SQLDelete(sql = "UPDATE `indexing_job` SET deleted = 1 WHERE id = ?")
@Getter
@Setter
@NoArgsConstructor
public class IndexingJob extends BaseEntity {

    /** 文档 id（document.id）。 */
    @Column(name = "doc_id", nullable = false)
    private Long docId;

    /** 知识库 id（knowledge_base.id）。 */
    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    /** 同批上传分组 id（批量重试用，区分哪些文件是一批上传的）。 */
    @Column(name = "batch_id", length = 64)
    private String batchId;

    /** 当前阶段：UPLOAD/PARSE/CHUNK/EMBED/STORE。 */
    @Column(name = "stage", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private Stage stage = Stage.UPLOAD;

    /** 进度 n/m（如 3/10，表示第 3 个切片 / 共 10 个）。 */
    @Column(name = "progress", length = 20)
    private String progress;

    /** 任务状态：QUEUED/RUNNING/SUCCESS/FAILED。 */
    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private Status status = Status.QUEUED;

    /** 失败所在阶段（精确标记死在哪环，UI 显示「❌ 解析失败：加密 PDF」而非笼统「处理失败」）。 */
    @Column(name = "fail_stage", length = 20)
    private String failStage;

    /** 细分错误码（加密 PDF / 扫描件无文本 / 格式损坏 / embedding 不可用 / 超时…）。 */
    @Column(name = "error_code", length = 50)
    private String errorCode;

    /** 失败原因明细。 */
    @Column(name = "error_message", length = 1024)
    private String errorMessage;

    /** 已重试次数（断点重试用）。 */
    @Column(name = "retry_count", nullable = false, columnDefinition = "int default 0")
    private Integer retryCount = 0;

    /** 索引阶段。 */
    public enum Stage {
        /** 已上传、待处理。 */
        UPLOAD,
        /** 解析文档。 */
        PARSE,
        /** 切片。 */
        CHUNK,
        /** 向量化。 */
        EMBED,
        /** 写入向量库。 */
        STORE
    }

    /** 任务状态。 */
    public enum Status {
        /** 排队中。 */
        QUEUED,
        /** 处理中。 */
        RUNNING,
        /** 成功。 */
        SUCCESS,
        /** 失败。 */
        FAILED
    }
}
