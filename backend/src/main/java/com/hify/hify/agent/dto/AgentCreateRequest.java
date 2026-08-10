package com.hify.hify.agent.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/**
 * 创建 Agent 请求（M4/T2，§3.5 入参契约）。
 *
 * <p>大白话：管理员填一张「新建 Agent」的表。带 {@code @NotNull} 的是硬必填，其余可空——
 * 空则服务层填默认值（启用、非默认、排序 0、温度 0.7、top_p 1.0、token 上限等）。
 * {@code secret} / {@code userPassword} 可空：不传则留空（多数内置 Agent 用不到外部秘钥）。
 */
public record AgentCreateRequest(

        @NotNull(message = "名称必填")
        String name,

        String description,

        @NotNull(message = "System Prompt 必填")
        String systemPrompt,

        @NotNull(message = "模型厂商必填")
        Long modelProviderId,

        @NotNull(message = "模型名必填")
        String model,

        String secret,

        String userPassword,

        Boolean enabled,

        Boolean defaultAgent,

        Integer sortOrder,

        BigDecimal temperature,

        BigDecimal topP,

        Integer maxTokens,

        Integer maxContextTokens,

        List<Long> knowledgeRefs,

        List<Long> toolRefs,

        List<Long> skillRefs) {
}
