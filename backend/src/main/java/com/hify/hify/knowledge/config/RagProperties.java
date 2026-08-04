package com.hify.hify.knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

/**
 * RAG 引擎全局默认参数（K2），前缀 {@code rag}。
 *
 * <p>大白话：这是一整排"旋钮"的出厂默认档位——切多大一块、找回几条、多低分就不答。
 * 改这里等于全局改；单个知识库想搞特殊，就在 {@code knowledge_base.rag_config} 里只写要改的那几项覆盖（见 {@link RagConfig}）。
 *
 * <p>为什么所有默认值都写成 {@code public static final} 常量：AGENTS.md §7.2 规则 7「魔法值零容忍」——
 * 全项目引用同一个常量，避免 800/0.6 这类数字散落在切片器、检索器、实体默认值里各写一份、改一处漏一处。
 *
 * <p>三层参数纪律（需求 §7）：
 * <ul>
 *   <li><b>建库参数</b>（{@code embeddingModel}/{@code vectorDim}/{@code chunkSize}/{@code chunkOverlap}）：
 *       改了必须全库重建向量，UI 需提示，见 {@link RagConfig#requiresRebuild(RagConfig)}。</li>
 *   <li><b>检索参数</b>（{@code retrievalTopK}/{@code finalTopN}/{@code rrfK}/{@code scoreThreshold}/{@code contextExpand}）：
 *       即时生效，无需重建。</li>
 *   <li><b>生成参数</b>（temperature 等）：不在此处，走 M3 ProviderClient 的模型配置。</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "rag")
@Getter
@Setter
public class RagProperties {

    /** 默认 embedding 模型（BGE-M3，1024 维中文效果好）。 */
    public static final String DEFAULT_EMBEDDING_MODEL = "bge-m3";

    /** 默认向量维度；第一天定死，运行时禁改（换模型=全库重建，需求 §6）。 */
    public static final int DEFAULT_VECTOR_DIM = 1024;

    /** 默认切块大小（字符）：一块能独立回答一个问题的经验值。 */
    public static final int DEFAULT_CHUNK_SIZE = 800;

    /** 默认切块重叠（字符）：约 15%，防止关键句被切断在两块之间。 */
    public static final int DEFAULT_CHUNK_OVERLAP = 120;

    /** 默认单路召回条数（向量路/FTS 路各取这么多再融合）。 */
    public static final int DEFAULT_RETRIEVAL_TOP_K = 10;

    /** 默认最终喂给大模型的片段数（融合排序后截断）。 */
    public static final int DEFAULT_FINAL_TOP_N = 4;

    /** 默认 RRF 融合常数：两路分数单位不可比，故按排名投票，k 越大越平滑。 */
    public static final int DEFAULT_RRF_K = 60;

    /** 默认相似度阈值：低于它一律拒答，宁可不答不瞎编（与 hify.embedding.similarity-threshold 保持同值）。 */
    public static final double DEFAULT_SCORE_THRESHOLD = 0.6;

    /** 默认上下文扩展块数（Small-to-Big：命中块的前后各补几块原文）。 */
    public static final int DEFAULT_CONTEXT_EXPAND = 1;

    /** FTS 全文检索路默认开关：开（人为置 false 才退化成纯向量单路）。 */
    public static final boolean DEFAULT_FTS_ENABLED = true;

    /** 中文分词配置名：zhparser 装得上时用它。 */
    public static final String TS_CONFIG_ZHPARSER = "zhparser_cfg";

    /** 兜底分词配置名：PG 内置、永远可用；中文按字切，召回略差但双路不塌。 */
    public static final String TS_CONFIG_SIMPLE = "simple";

    /** embedding 模型名（建库参数，改了要重建）。 */
    private String embeddingModel = DEFAULT_EMBEDDING_MODEL;

    /** 向量维度（建库参数，与 pgvector vector(1024) 强绑定，禁运行时改）。 */
    private int vectorDim = DEFAULT_VECTOR_DIM;

    /** 切块大小，单位字符（建库参数，改了要重建）。 */
    private int chunkSize = DEFAULT_CHUNK_SIZE;

    /** 切块重叠，单位字符（建库参数，改了要重建）。 */
    private int chunkOverlap = DEFAULT_CHUNK_OVERLAP;

    /** 单路召回条数（检索参数，即时生效）。 */
    private int retrievalTopK = DEFAULT_RETRIEVAL_TOP_K;

    /** 最终喂模型的片段数（检索参数，即时生效）。 */
    private int finalTopN = DEFAULT_FINAL_TOP_N;

    /** RRF 融合常数（检索参数，即时生效）。 */
    private int rrfK = DEFAULT_RRF_K;

    /** 相似度阈值，低于则拒答（检索参数，即时生效）。 */
    private double scoreThreshold = DEFAULT_SCORE_THRESHOLD;

    /** 上下文扩展块数（检索参数，即时生效）。 */
    private int contextExpand = DEFAULT_CONTEXT_EXPAND;

    /** 全文检索（FTS）路配置。 */
    private Fts fts = new Fts();

    /**
     * FTS（PostgreSQL 全文检索）路配置。
     *
     * <p>{@code enabled} 是人工总开关；{@code tsConfig} 由 {@link ZhparserProbe} 在启动时按探测结果自动写入，
     * 不需要人工维护——装得上 zhparser 就是 {@link #TS_CONFIG_ZHPARSER}，装不上自动降级 {@link #TS_CONFIG_SIMPLE}。
     */
    @Getter
    @Setter
    public static class Fts {

        /** 是否启用 FTS 路（默认 true；置 false 则检索退化为纯向量单路）。 */
        private boolean enabled = DEFAULT_FTS_ENABLED;

        /** {@code to_tsvector(tsConfig, ...)} 用的分词配置名，K4 检索 SQL 从这里取。 */
        private String tsConfig = TS_CONFIG_ZHPARSER;
    }
}
