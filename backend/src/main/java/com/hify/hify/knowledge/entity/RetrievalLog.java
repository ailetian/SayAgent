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

import java.math.BigDecimal;

/**
 * 检索日志 + 拒答分析（K1/K5）。
 *
 * <p>大白话：每次用户提问检索一次，就把「问了啥 / 改写了啥 / 命中了哪些片段 / 答了啥 /
 * 为什么拒答」记一条，用来复盘检索质量、分析拒答率。不建外键——日志应存活于知识库 /
 * Agent 软删之后，独立于业务实体生命周期。
 */
@Entity
@Table(name = "retrieval_log")
@SQLRestriction("deleted = 0")
@SQLDelete(sql = "UPDATE `retrieval_log` SET deleted = 1 WHERE id = ?")
@Getter
@Setter
@NoArgsConstructor
public class RetrievalLog extends BaseEntity {

    /** 知识库 id。 */
    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    /** Agent id。 */
    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    /** 用户原始提问。 */
    @Column(name = "query", nullable = false, length = 1000)
    private String query;

    /** Query Rewriting 后的查询（R4）。 */
    @Column(name = "rewritten", length = 1000)
    private String rewritten;

    /** 命中片段（json 数组，溯源 R6）。 */
    @Column(name = "hit_chunks", columnDefinition = "text")
    private String hitChunks;

    /** 各片段得分（json 数组）。 */
    @Column(name = "scores", columnDefinition = "text")
    private String scores;

    /** 最终回答（用于复盘）。 */
    @Column(name = "answer", columnDefinition = "mediumtext")
    private String answer;

    /** 检索耗时(ms)。 */
    @Column(name = "cost_ms")
    private Long costMs;

    /** 是否拒答（R3）。 */
    @Column(name = "rejected")
    private Boolean rejected;

    /** 拒答原因 NO_KB/NO_HIT/BELOW_THRESHOLD。 */
    @Column(name = "refusal_reason", length = 20)
    @Enumerated(EnumType.STRING)
    private RefusalReason refusalReason;

    /** 最高得分（与拒答线对比用）。 */
    @Column(name = "top_score", precision = 6, scale = 4)
    private BigDecimal topScore;

    /** 本次生效的拒答阈值。 */
    @Column(name = "threshold", precision = 6, scale = 4)
    private BigDecimal threshold;

    /** 候选片段（json，拒答分析用）。 */
    @Column(name = "top_candidates", columnDefinition = "text")
    private String topCandidates;

    /** 拒答原因。 */
    public enum RefusalReason {
        /** 没有可用知识库。 */
        NO_KB,
        /** 检索无命中。 */
        NO_HIT,
        /** 最高分低于拒答阈值。 */
        BELOW_THRESHOLD
    }
}
