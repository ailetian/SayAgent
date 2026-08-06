package com.hify.hify.knowledge.retriever;

import com.hify.hify.knowledge.config.RagConfig;

import java.util.List;


/**
 * 知识库向量检索端口（M5/T4）。
 *
 * <p>大白话：这是「按问题找相关片段」的接口定义。T4 只定义「要做什么」，具体怎么做（查哪张表、用哪个向量库）
 * 交给 {@code PgVectorRetrievalPort} 实现，方便以后换向量库，也方便单测用 Mockito 替换掉真实数据库。
 */
public interface RetrievalPort {

    /**
     * 检索与查询向量最相似的 Top-k 个文档片段（K11 软删预过滤版）。
     *
     * <p>相似度用余弦相似度（pg 向量库 {@code <=>} 算子即余弦距离）衡量，返回结果按相似度降序排列。
     * {@code allowedDocIds} 是"<b>当前知识库内未被软删的文档业务 id</b>"清单（由调用方从 MySQL 取后下推，
     * 业务表在 MySQL、chunk 在 PG，两库无法 JOIN），PG 用 {@code document_id IN (...)} 过滤掉孤儿 chunk
     * （被软删文档的切片绝不召回，K11 缺陷 A）。清单为空则直接返回空，绝不发生未过滤的查询。
     *
     * @param queryEmbedding 查询文本的向量（由 EmbeddingService 产出），非空、维度与入库切片一致
     * @param allowedDocIds  当前知识库内未软删的文档业务 id 列表（与 document_chunk.document_id 同口径）
     * @param topK           返回片段数，&gt;=1
     * @param threshold      相似度阈值，低于此值的 chunk 在检索时被过滤（T4 验收点3）
     * @return 按余弦相似度降序的片段列表（含相似度 score）；无命中返回空列表，不返回 null
     */
    List<RetrievedChunk> retrieve(float[] queryEmbedding, List<String> allowedDocIds, int topK, double threshold);

    /**
     * 接收原始查询文本，端口内完成向量化后检索（供 conversation 等调用方免感知 EmbeddingService）。
     *
     * <p>与 {@link #retrieve(float[], List, int, double)} 同语义，仅入口是原始文本（端口内向量化），
     * 同样按 {@code allowedDocIds} 做软删预过滤。
     *
     * @param queryText      原始查询文本（如最新一条用户消息）
     * @param allowedDocIds  当前知识库内未软删的文档业务 id 列表
     * @param topK           返回片段数，&gt;=1
     * @param threshold      相似度阈值，低于此值的 chunk 在检索时被过滤
     * @return 按余弦相似度降序的片段列表（含相似度 score）；无命中返回空列表，不返回 null
     */
    List<RetrievedChunk> retrieve(String queryText, List<String> allowedDocIds, int topK, double threshold);

    /**
     * 混合检索（K4，R2 双路 + RRF 融合 + 跨库软删下推 P1）。
     *
     * <p>大白话：一条查询同时走两条腿——语义向量路（pgvector 余弦找"意思像"的）和关键词路
     * （PG FTS 找"字面对"的），两路各取 top-k 后用 RRF（按名次投票，单位不可比只投名次）融成一份列表。
     * 跨库软删走两段式：先在 MySQL 按挂载的知识库筛出"未被软删"的有效 {@code document_id} 列表，
     * 再下推到 PG 的 {@code WHERE document_id IN (...)}——业务表在 MySQL、chunk 在 PG，
     * 两个独立库无法一条 SQL 互相子查询，必须分两步（见 {@code PgVectorRetrievalPort}）。
     *
     * @param queryText      原始查询文本（端口内完成向量化，调用方免感知 EmbeddingService）
     * @param mountedKbIds   当前 Agent 已挂载的知识库 id 列表（多库联合检索 + 软删隔离维度）
     * @param ragConfig      本次检索生效的 RAG 参数（topK / 阈值 / rrfK / fts 开关 / 分词器），从 {@link RagConfig} 取
     * @return 融合后按相关度降序的命中列表（含来源路标记）；无命中返回空列表，不返回 null
     */
    List<RetrievalResult> retrieveHybrid(String queryText, List<Long> mountedKbIds, RagConfig ragConfig);

    /**
     * 检索命中的单个片段（含余弦相似度），作为 RetrievalPort 的返回载体。
     *
     * @param documentId  片段所属文档 ID
     * @param chunkIndex  片段序号（对应入库时的 seq）
     * @param content     片段文本
     * @param score       余弦相似度，取值 [-1, 1]，越大越相关
     */
    record RetrievedChunk(String documentId, int chunkIndex, String content, double score) {
    }
}
