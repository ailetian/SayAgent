package com.hify.hify.knowledge.entity;

import com.hify.hify.common.base.BaseEntity;

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
 * 知识库文档（M5 T1/T3）。
 *
 * <p>大白话：一次上传就是一条 document；它先落库（状态 INDEXING），异步切片向量化后写入 pg 向量库，
 * 成功改 INDEXED、失败改 FAILED。document_id 是对外暴露的业务主键（UUID），与自增 id 解耦。
 * 软删除同 KnowledgeBase（§6.1）。
 */
@Entity
@Table(name = "document")
@SQLRestriction("deleted = 0")
@SQLDelete(sql = "UPDATE `document` SET deleted = 1 WHERE id = ?")
@Getter
@Setter
@NoArgsConstructor
public class Document extends BaseEntity {

    /** 对外暴露的业务文档 id（UUID），调用方上传/查询状态都用它。 */
    @Column(name = "document_id", nullable = false, length = 64)
    private String documentId;

    /** 所属知识库 id（knowledge_base.id）。 */
    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    /** 文档标题。 */
    @Column(name = "title", nullable = false, length = 255)
    private String title;

    /** 来源类型：FILE/URL/TEXT。 */
    @Column(name = "source_type", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private SourceType sourceType;

    /** 来源引用（文件路径 / URL），可空。 */
    @Column(name = "source_ref", length = 512)
    private String sourceRef;

    /** 索引状态：UPLOADED/INDEXING/INDEXED/FAILED（落 document.status）。 */
    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private DocumentStatus status;

    /** 切片数（向量化完成后回填）。 */
    @Column(name = "chunk_count", nullable = false, columnDefinition = "int default 0")
    private Integer chunkCount = 0;

    /** 索引失败原因（INDEXING→FAILED 时回填，便于排查，对应审核报告 P1-4）。 */
    @Column(name = "error_message", length = 1024)
    private String errorMessage;

    /** 文件校验和（R8 去重，防重复向量）。 */
    @Column(name = "checksum", length = 64)
    private String checksum;

    /** 文件大小（字节，列表页展示 + 20MB 校验）。 */
    @Column(name = "size_bytes")
    private Long sizeBytes = 0L;

    /** 文件 MIME（白名单校验记录）。 */
    @Column(name = "mime_type", length = 100)
    private String mimeType;

    /** 单文档 token 成本。 */
    @Column(name = "token_count")
    private Integer tokenCount = 0;

    /** 文档来源类型（上传时指定）。 */
    public enum SourceType {
        /** 文件上传（txt/md/pdf）。 */
        FILE,
        /** 网页 URL。 */
        URL,
        /** 直接粘贴文本。 */
        TEXT
    }

    /** 文档索引状态。 */
    public enum DocumentStatus {
        /** 已上传、待索引。 */
        UPLOADED,
        /** 切片向量化中。 */
        INDEXING,
        /** 向量已全部写入 pg，可检索。 */
        INDEXED,
        /** 向量化失败。 */
        FAILED
    }
}
