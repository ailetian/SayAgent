package com.hify.hify.agent.dto;

import com.hify.hify.agent.entity.Agent;
import com.hify.hify.agent.service.AgentService.AgentSensitivitySummary;
import com.hify.hify.common.tool.DataSensitivity;
import com.hify.hify.common.tool.RiskLevel;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent 对外视图（M4/T2，§3.5 响应契约）。
 *
 * <p>大白话：返回给前端的「Agent 名片」。刻意<b>不含</b> {@code secret} / {@code userPassword}
 * —— 这两列是调外部 Agent 用的秘钥，绝不能出内网（§7.11 脱敏）。
 *
 * <p>M10/T6：额外携带<b>聚合敏感度字段</b>（maxDataSensitivity / maxRiskLevel / financeHrToolCount /
 * confidentialToolCount），供前端 Agent 列表渲染「含财务·人事域工具」徽章，<b>由后端聚合、前端不自行计算</b>（§3.2）。
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
        List<Long> skillRefs,
        /** 聚合最高数据敏感度枚举名（PUBLIC/INTERNAL/CONFIDENTIAL/FINANCE_HR），M10/T6。 */
        String maxDataSensitivity,
        /** 聚合最高危险度枚举名（L0_READONLY_SAFE..L3_HIGH_RISK），M10/T6。 */
        String maxRiskLevel,
        /** 含财务·人事域工具数，M10/T6。 */
        long financeHrToolCount,
        /** 含机密域工具数，M10/T6。 */
        long confidentialToolCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    /** 把内部实体翻译成对外 VO（秘钥字段天然缺失）。 */
    public static AgentVO from(Agent a) {
        return from(a, new AgentSensitivitySummary(
                DataSensitivity.INTERNAL, RiskLevel.L0_READONLY_SAFE, 0L, 0L));
    }

    /** 带聚合敏感度快照的翻译（M10/T6 列表/详情用，避免重复查库）。 */
    public static AgentVO from(Agent a, AgentSensitivitySummary s) {
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
                a.getSkillRefs(),
                s.maxDataSensitivity() != null ? s.maxDataSensitivity().name() : DataSensitivity.INTERNAL.name(),
                s.maxRiskLevel() != null ? s.maxRiskLevel().name() : RiskLevel.L0_READONLY_SAFE.name(),
                s.financeHrToolCount(),
                s.confidentialToolCount(),
                a.getCreatedAt(),
                a.getUpdatedAt());
    }
}
