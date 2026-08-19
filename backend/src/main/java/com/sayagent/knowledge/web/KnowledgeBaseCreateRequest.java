package com.sayagent.knowledge.web;

import com.sayagent.knowledge.entity.KnowledgeBase.ChunkStrategy;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 建库请求（K8 两步创建·第一步）。
 *
 * <p>大白话：前端填「库名 + 可选描述/模型/阈值/切片策略/语言/是否可挂载」，先建一个合法的空库；
 * 文档随后再上传（第二步）。校验用 Bean Validation（§7.11 规则36），阈值等参数不写就用全局默认。
 */
public record KnowledgeBaseCreateRequest(

        @NotBlank(message = "知识库名称不能为空")
        @Size(max = 80, message = "知识库名称最多 80 字")
        String name,

        @Size(max = 500, message = "描述最多 500 字")
        String description,

        /** embedding 模型名（可空，取全局默认 BGE-M3）。 */
        String embeddingModel,

        /** 相似度阈值（可空，取全局默认 0.6）。 */
        BigDecimal similarityThreshold,

        /** 切片策略（可空，默认 AUTO）。 */
        ChunkStrategy chunkStrategy,

        /** 文档语言（可空，默认 zh-CN）。 */
        String language,

        /** 是否可被挂载到 Agent（可空，默认 true）。 */
        Boolean isPublic
) {
}
