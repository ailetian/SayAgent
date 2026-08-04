package com.hify.hify.knowledge.retriever;

/**
 * 混合检索单条命中（K4，R2 双路 + RRF 融合后的统一结果载体）。
 *
 * <p>大白话：语义向量路（pgvector 余弦）和关键词路（PG FTS）各自召回一批片段，
 * 经 RRF 融合后产出"一份 ranked 列表"——每一条都带着它"主要来自哪一路"的标记，
 * 方便 K5 做 Small-to-Big(R5) 取大块、溯源(R6) 标注来源。
 *
 * @param documentId  片段所属文档的业务 id
 * @param chunkIndex  片段序号（对应入库时的 seq）
 * @param content     片段文本
 * @param score       融合后的综合分：双路时为 RRF 加权的名次分；纯向量单路时退化为余弦相似度
 * @param rank        最终融合名次（1 起，越小越相关）
 * @param source      该片段主要来自哪一路召回（SEMANTIC 语义向量 / FTS 关键词）
 */
public record RetrievalResult(
        String documentId,
        int chunkIndex,
        String content,
        double score,
        int rank,
        RetrievalSource source) {

    /** 该命中片段的来源路（K4 双路召回标记）。 */
    public enum RetrievalSource {
        /** 语义向量路（pgvector 余弦相似度）。 */
        SEMANTIC,
        /** 关键词全文路（PG FTS，zhparser/simple 分词）。 */
        FTS
    }
}
