package com.sayagent.knowledge.chunk;

/**
 * 一个切片（K3）。
 *
 * <p>大白话：文档被切成很多「卡片」，每张卡片就是一块能独立回答一个问题的小文本。
 * {@code seq} 是它在全文里的连续序号（1,2,3...），写进 pg 的 {@code document_chunk.seq} 列，
 * 后面 Small-to-Big(R5) 扩写上下文、检索结果排序都要靠它。
 *
 * @param seq     连续序号，从 1 开始
 * @param content 切片正文（非空）
 */
public record Chunk(int seq, String content) {

    public Chunk {
        if (seq < 1) {
            throw new IllegalArgumentException("seq 必须 >= 1");
        }
        if (content == null) {
            content = "";
        }
    }
}
