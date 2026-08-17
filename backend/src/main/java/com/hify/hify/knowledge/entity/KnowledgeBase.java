package com.hify.hify.knowledge.entity;

import com.hify.hify.common.base.BaseEntity;
import com.hify.hify.knowledge.config.RagConfig;
import com.hify.hify.knowledge.config.RagProperties;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 知识库（M5 T1）。
 *
 * <p>大白话：一个知识库就是一堆文档的容器，记录用哪个 embedding 模型、向量维度、检索相似度阈值。
 * 软删除：{@code deleted=1} 由 @SQLDelete 在 JPA 删除时自动置位，@SQLRestriction 让查询默认只看未删的（§6.1）。
 */
@Entity
@Table(name = "knowledge_base")
@SQLRestriction("deleted = 0")
@SQLDelete(sql = "UPDATE `knowledge_base` SET deleted = 1 WHERE id = ?")
@Getter
@Setter
@NoArgsConstructor
public class KnowledgeBase extends BaseEntity {

    /** similarity_threshold 列的小数位数（DDL: decimal(4,3)）。 */
    private static final int SIMILARITY_SCALE = 3;

    /**
     * 相似度阈值默认值：直接引用 {@link RagProperties#DEFAULT_SCORE_THRESHOLD}，
     * 保证"实体默认值"与"全局配置默认值"永远同一个数，杜绝两处不一致（K2 验收点）。
     */
    private static final BigDecimal DEFAULT_SIMILARITY_THRESHOLD =
            BigDecimal.valueOf(RagProperties.DEFAULT_SCORE_THRESHOLD)
                    .setScale(SIMILARITY_SCALE, RoundingMode.HALF_UP);

    /** 可见性：全员可见（§2.1）。 */
    public static final String VISIBILITY_PUBLIC = "PUBLIC";
    /** 可见性：仅授权可见（默认，secure by default §2.1）。 */
    public static final String VISIBILITY_RESTRICTED = "RESTRICTED";

    /** 知识库名称（必填，<=80 字）。 */
    @Column(name = "name", nullable = false, length = 80)
    private String name;

    /** 描述（可空，默认空串）。 */
    @Column(name = "description", nullable = false, columnDefinition = "varchar(500) default ''")
    private String description = "";

    /** embedding 模型名（如 text-embedding-3-small；可空，取默认 embedding 模型）。 */
    @Column(name = "embedding_model", length = 100)
    private String embeddingModel;

    /** 向量维度（默认 1024，须与 pgvector 的 vector(1024) 一致）。 */
    @Column(name = "embedding_dim", nullable = false, columnDefinition = "int default 1024")
    private Integer embeddingDim = RagProperties.DEFAULT_VECTOR_DIM;

    /** 检索相似度阈值（默认取全局 {@code rag.score-threshold}，低于此值的 chunk 在检索时被过滤）。 */
    @Column(name = "similarity_threshold", nullable = false, precision = 4, scale = SIMILARITY_SCALE,
            columnDefinition = "decimal(4,3) default 0.600")
    private BigDecimal similarityThreshold = DEFAULT_SIMILARITY_THRESHOLD;

    /** 创建者登录名(username)；管理权 = 创建者 或 管理员（K7 退役 KbAccess 后，查询权委托 Agent 挂载）。 */
    @Column(name = "creator_id", length = 64)
    private String creatorId;

    /** 库级 RAG 参数（JSON 文本，参数会增减免改表；K2 解析为 RagConfig 对象）。 */
    @Column(name = "rag_config")
    @Convert(converter = JsonRawConverter.class)
    private String ragConfig;

    /** 切片策略：AUTO/RECURSIVE/MARKDOWN_HEADER（§7 第一层，建库定死）。 */
    @Column(name = "chunk_strategy", length = 20)
    @Enumerated(EnumType.STRING)
    private ChunkStrategy chunkStrategy = ChunkStrategy.AUTO;

    /** 文档语言，影响分词（默认 zh-CN）。 */
    @Column(name = "language", length = 20)
    private String language = "zh-CN";

    /** 库内 token 总量（成本/配额可见）。 */
    @Column(name = "token_count")
    private Long tokenCount = 0L;

    /** 库状态：ACTIVE/ARCHIVED（删除已由 deleted 软删覆盖，§8.1）。 */
    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private Status status = Status.ACTIVE;

    /** 可否被挂载到 Agent（§3.5 预留），默认 true。 */
    @Column(name = "is_public", nullable = false, columnDefinition = "tinyint(1) default 1")
    private Boolean isPublic = true;

    /**
     * 可见性（T6 列表过滤的唯一真相源，§2.1）：PUBLIC=全员可见 / RESTRICTED=仅授权可见（默认）。
     * V31 已为该列建索引 idx_visibility；列表过滤以本字段为准，与 isPublic 解耦（避免历史 is_public 语义歧义）。
     */
    @Column(name = "visibility", nullable = false, length = 20)
    private String visibility = VISIBILITY_RESTRICTED;

    /**
     * 算出本知识库这次真正生效的 RAG 参数：库级 {@code rag_config} 有写的用库级，没写的回退全局默认（K2）。
     *
     * <p>大白话：全局是"公司统一标准"，库级是"这个库的特批条款"，只在特批里写了的项才特殊，其余照标准执行。
     * 库级 JSON 写坏了不会抛异常，会打 WARN 后整份回退全局（见 {@link RagConfig#merge(RagConfig, String)}）。
     *
     * @param globalDefault 全局默认参数快照，通常由 {@code RagConfig.fromGlobal(ragProperties)} 得到
     * @return 合并后的生效参数
     */
    public RagConfig getEffectiveConfig(RagConfig globalDefault) {
        return RagConfig.merge(globalDefault, this.ragConfig);
    }

    /** 切片策略。 */
    public enum ChunkStrategy {
        /** 按文档类型自动选切法。 */
        AUTO,
        /** 递归字符切分（PDF/DOCX 兜底）。 */
        RECURSIVE,
        /** 按 Markdown 标题层级切分。 */
        MARKDOWN_HEADER
    }

    /** 知识库状态。 */
    public enum Status {
        /** 正常可用。 */
        ACTIVE,
        /** 已归档（不再参与检索）。 */
        ARCHIVED
    }
}
