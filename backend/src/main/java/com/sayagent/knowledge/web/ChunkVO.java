package com.sayagent.knowledge.web;

/**
 * 检索命中的片段视图对象（M5/T5）。
 *
 * @param score       余弦相似度 [-1, 1]，越大越相关
 * @param content     片段文本
 * @param documentId  所属文档 ID
 * @param chunkIndex  片段序号（对应入库时的 seq）
 */
public record ChunkVO(double score, String content, String documentId, int chunkIndex) {
}
