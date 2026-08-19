package com.sayagent.knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

/**
 * Embedding 配置（M5 T2），前缀 {@code sayagent.embedding}。
 *
 * <p>大白话：维度 1024（与 pgvector 的 vector(1024) 一致）、相似度阈值 0.6（检索时过滤低于它的 chunk）、
 * 单切片最大字符 maxChunkSize=1000、一次 embed 调用最多 20 条 batchSize=20。带默认值，缺失也能跑。
 *
 * <p><b>与 {@link RagProperties} 的关系（K2 起）</b>：RAG 引擎参数的唯一真相源是 {@code rag.*}。
 * 本类是 M5 T2 {@code EmbeddingService} 的遗留入参，为杜绝"同一个参数两处写不同值"，
 * {@code dimension}/{@code similarityThreshold} 的默认值直接引用 {@code RagProperties} 常量；
 * {@code maxChunkSize} 是 T2 的纯长度粗切参数（与语义切块不是一回事），K3 语义切片器落地后由 {@code rag.chunk-size} 接管。
 */
@Component
@ConfigurationProperties(prefix = "sayagent.embedding")
@Getter
@Setter
public class EmbeddingConfig {

    /** M5 T2 粗切默认字符数（非语义切块；K3 后由 rag.chunk-size 接管）。 */
    private static final int DEFAULT_LEGACY_MAX_CHUNK_SIZE = 1000;

    /** 一次 embed 调用的默认批大小。 */
    private static final int DEFAULT_BATCH_SIZE = 20;

    /** 向量维度（默认取 {@code rag.vector-dim} 同值，须与 pgvector vector(1024) 一致）。 */
    private int dimension = RagProperties.DEFAULT_VECTOR_DIM;

    /** 检索相似度阈值（默认取 {@code rag.score-threshold} 同值，低于此值的 chunk 在检索时过滤）。 */
    private double similarityThreshold = RagProperties.DEFAULT_SCORE_THRESHOLD;

    /** 单切片最大字符数（默认 1000，超过则切分）。 */
    private int maxChunkSize = DEFAULT_LEGACY_MAX_CHUNK_SIZE;

    /** 一次 embed 调用最多条数（默认 20，达到才发一次请求）。 */
    private int batchSize = DEFAULT_BATCH_SIZE;

    /** 默认 embedding 模型名（可选；为空时由 ProviderRouter 选默认模型）。 */
    private String defaultEmbeddingModel = "";
}
