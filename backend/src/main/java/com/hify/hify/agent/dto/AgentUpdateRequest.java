package com.hify.hify.agent.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 修改 Agent 请求（M4/T2，§3.5 入参契约）。
 *
 * <p>大白话：管理员改某张 Agent 配置。所有字段均可空——传了才改，没传保留原值（部分更新）。
 * {@code secret} / {@code userPassword} 若传 null 表示保留原秘钥（不修改），传非 null 表示更新。
 */
public record AgentUpdateRequest(

        String name,

        String description,

        String systemPrompt,

        Long modelProviderId,

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
