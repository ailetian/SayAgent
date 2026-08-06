package com.hify.hify.knowledge.web;

import com.hify.hify.knowledge.entity.KnowledgeBase.ChunkStrategy;

import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 知识库更新请求（K11 收口 K8 缺口②）。
 *
 * <p>大白话：所有字段都可空——前端改哪一项就传哪一项，后端只覆盖非空字段（不整体替换）。
 * 校验用 Bean Validation（§7.11 规则36），阈值等参数不传就保持原值。
 */
public record KnowledgeBaseUpdateRequest(

        @Size(max = 80, message = "知识库名称最多 80 字")
        String name,

        @Size(max = 500, message = "描述最多 500 字")
        String description,

        /** embedding 模型名（可空，保持原值）。 */
        String embeddingModel,

        /** 相似度阈值（可空，保持原值）。 */
        BigDecimal similarityThreshold,

        /** 切片策略（可空，保持原值）。 */
        ChunkStrategy chunkStrategy,

        /** 文档语言（可空，保持原值）。 */
        String language,

        /** 是否可被挂载到 Agent（可空，保持原值）。 */
        Boolean isPublic
) {
}
