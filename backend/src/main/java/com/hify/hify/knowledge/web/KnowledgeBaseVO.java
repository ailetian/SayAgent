package com.hify.hify.knowledge.web;

import com.hify.hify.knowledge.entity.KnowledgeBase;
import com.hify.hify.knowledge.entity.KnowledgeBase.ChunkStrategy;
import com.hify.hify.knowledge.entity.KnowledgeBase.Status;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 知识库视图对象（K8）。
 *
 * <p>大白话：前端要的「库长什么样」的精简盒子。
 * 严格遵循 §7.11 规则37（秘钥隔离）：<b>绝不</b>序列化 {@code rag_config} 原始 JSON、
 * 任何 api_key / token / 秘钥字段，避免敏感信息泄漏到前端。
 *
 * @param id                   知识库 id
 * @param name                 名称
 * @param description          描述
 * @param embeddingModel       embedding 模型名（非秘钥，可展示）
 * @param embeddingDim         向量维度（建库参数，非秘钥）
 * @param similarityThreshold  相似度阈值
 * @param creatorId            创建者（展示用，非秘钥）
 * @param chunkStrategy        切片策略
 * @param language             文档语言
 * @param status               库状态 ACTIVE/ARCHIVED
 * @param isPublic             可否被挂载
 * @param createdAt            创建时间
 */
public record KnowledgeBaseVO(
        Long id,
        String name,
        String description,
        String embeddingModel,
        Integer embeddingDim,
        BigDecimal similarityThreshold,
        String creatorId,
        ChunkStrategy chunkStrategy,
        String language,
        Status status,
        Boolean isPublic,
        LocalDateTime createdAt
) {
}
