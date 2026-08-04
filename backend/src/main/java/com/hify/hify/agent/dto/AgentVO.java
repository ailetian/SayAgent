package com.hify.hify.agent.dto;

import com.hify.hify.agent.entity.Agent;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent 对外视图（M4/T2，§3.5 响应契约）。
 *
 * <p>大白话：返回给前端的「Agent 名片」。刻意<b>不含</b> {@code secret} / {@code userPassword}
 * —— 这两列是调外部 Agent 用的秘钥，绝不能出内网（§7.11 脱敏）。
 */
public record AgentVO(
        Long id,
        String name,
        String description,
        String systemPrompt,
        Long modelProviderId,
        String model,
        Boolean enabled,
        Boolean defaultAgent,
        Integer sortOrder,
        BigDecimal temperature,
        BigDecimal topP,
        Integer maxTokens,
        Integer maxContextTokens,
        List<Long> knowledgeRefs,
        List<Long> toolRefs,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    /** 把内部实体翻译成对外 VO（秘钥字段天然缺失）。 */
    public static AgentVO from(Agent a) {
        return new AgentVO(
                a.getId(),
                a.getName(),
                a.getDescription(),
                a.getSystemPrompt(),
                a.getModelProviderId(),
                a.getModel(),
                a.getEnabled(),
                a.getDefaultAgent(),
                a.getSortOrder(),
                a.getTemperature(),
                a.getTopP(),
                a.getMaxTokens(),
                a.getMaxContextTokens(),
                a.getKnowledgeRefs(),
                a.getToolRefs(),
                a.getCreatedAt(),
                a.getUpdatedAt());
    }
}
