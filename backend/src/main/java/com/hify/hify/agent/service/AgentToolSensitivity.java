package com.hify.hify.agent.service;

/**
 * 某 Agent 携带的单个工具「危险度 + 数据敏感度」快照（M10/T6，供前端授权页摊开知情）。
 *
 * <p>大白话：把后端聚合出来的每个工具名 + 它的危险度等级 + 数据敏感度，打包成一条给前端展示用。
 * 取值直接取自 MCP 工具的 {@code riskLevel}/{@code dataSensitivity}（枚举 {@code name()}，前端按名渲染色标）。
 */
public record AgentToolSensitivity(
        /** 工具名（如 读薪酬 / 取消订单）。 */
        String name,
        /** 工具说明。 */
        String description,
        /** 危险度枚举名：L0_READONLY_SAFE / L1_WRITE_REVERSIBLE / L2_IRREVERSIBLE / L3_HIGH_RISK。 */
        String riskLevel,
        /** 数据敏感度枚举名：PUBLIC / INTERNAL / CONFIDENTIAL / FINANCE_HR。 */
        String dataSensitivity) {
}
