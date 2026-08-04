package com.hify.hify.knowledge.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 知识库检索请求（M5/T5）。
 *
 * <p>大白话：前端把「问题 + 想查哪个知识库 + 要几个片段」装进这个盒子发过来。
 * 注：计划文档原以 tenant_id 描述隔离维度，已合入的 T1 实际落地为 document_chunk.kb_id，
 * 故此处用 kbId 与之对齐（单租户下 kb 即隔离维度）。
 */
public record RetrieveRequest(

        @NotBlank(message = "query 不能为空")
        String query,

        @NotNull(message = "kbId 不能为空")
        Long kbId,

        @Min(value = 1, message = "topK 至少为 1")
        @Max(value = 50, message = "topK 最多 50")
        Integer topK) {

    /** 未传 topK 时取默认 5（与计划一致）。 */
    public int topKOrDefault() {
        return topK == null ? 5 : topK;
    }
}
