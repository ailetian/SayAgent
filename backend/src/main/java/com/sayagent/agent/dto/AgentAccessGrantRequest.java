package com.sayagent.agent.dto;

import jakarta.validation.constraints.NotNull;

import java.util.Set;

/**
 * Agent 授权请求（M10/T6，新增 {@code /api/agents/{id}/access} 端点用）。
 *
 * <p>大白话：管理员把某个 Agent 授权给「某个角色 / 某个用户」时填的表——
 * 谁（principalType + principalId）、给哪四权。后端会据此写审计（含该 Agent 携带的敏感工具摘要）。
 *
 * <p>与 {@code ResourceAccessRequest}（rbac 通用授权）字段对齐，但本 DTO 专属于 Agent 场景，
 * 由 Agent 模块自行受理后再委托 rbac 落库（便于在授权时计算并透传风险摘要，§3.2 跨模块只依赖 rbac 接口）。
 */
public record AgentAccessGrantRequest(

        /** 授权主体类型：ROLE / USER（与 rbac 常量对齐，§7.2 禁魔法值）。 */
        @NotNull String principalType,

        /** 授权主体 id：角色名(ADMIN/OPERATOR/USER) / 登录名。 */
        @NotNull String principalId,

        /** 可读。 */
        boolean canRead,

        /** 可写。 */
        boolean canWrite,

        /** 可用（对话/调用）。 */
        boolean canUse,

        /** 可编辑配置。 */
        boolean canEdit) {

    /** 允许的主体类型（§7.2 禁魔法值）。 */
    public static final Set<String> PRINCIPAL_TYPES = Set.of("ROLE", "USER");
}
