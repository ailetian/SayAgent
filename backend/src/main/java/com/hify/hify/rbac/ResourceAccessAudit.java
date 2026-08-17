package com.hify.hify.rbac;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 资源授权审计日志（M10/T6，§7.11 重要操作留痕）。
 *
 * <p>大白话：每次「谁把哪个 Agent/知识库 授权给 谁」都写一行到这里，事后可查可追责。
 * 本表为<b>追加写</b>日志——不更新、不软删（无 {@code deleted}/{@code updated_at} 列，
 * 与同包 {@link ResourceAccess} 一致，二者都不继承 {@code BaseEntity}），故不加
 * {@code @SQLRestriction}/{@code @SQLDelete}（审计行永不被过滤）。
 *
 * <p>{@code risk_summary} 记录被授权资源携带的敏感工具摘要（如「含财务·人事域工具 3 个」），
 * 由调用方（agent 模块）在授权时经 {@code ResourceAccessService.grant(...)} 透传计算，
 * rbac 不反向依赖任何业务包（§3.2）。
 */
@Entity
@Table(name = "resource_access_audit")
@Getter
@Setter
@NoArgsConstructor
public class ResourceAccessAudit {

    /** 主键：BIGINT 自增（与 §6.1 一致，非 UUID 防索引碎片）。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 操作时间（追加写，不可更新）。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /** 操作人（管理员登录名，来自 AuthContext）。 */
    @Column(name = "operator", nullable = false, length = 64)
    private String operator;

    /** 操作类型：GRANT / REVOKE。 */
    @Column(name = "action", nullable = false, length = 20)
    private String action;

    /** 授权主体类型：ROLE / USER。 */
    @Column(name = "principal_type", nullable = false, length = 20)
    private String principalType;

    /** 授权主体 id：角色名 / 登录名。 */
    @Column(name = "principal_id", nullable = false, length = 64)
    private String principalId;

    /** 资源类型：KB / AGENT。 */
    @Column(name = "resource_type", nullable = false, length = 20)
    private String resourceType;

    /** 资源 id：knowledge_base.id / agent.id。 */
    @Column(name = "resource_id", nullable = false)
    private Long resourceId;

    /** 被授权资源携带的敏感工具摘要（仅 AGENT 有意义，KB 为 null）。 */
    @Column(name = "risk_summary", length = 512)
    private String riskSummary;

    /** 备注（可选，如授权四权位摘要）。 */
    @Column(name = "detail", length = 512)
    private String detail;

    /**
     * 全参构造（审计写盘用）。
     */
    public ResourceAccessAudit(String operator, String action, String principalType, String principalId,
                               String resourceType, Long resourceId, String riskSummary) {
        this.operator = operator;
        this.action = action;
        this.principalType = principalType;
        this.principalId = principalId;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.riskSummary = riskSummary;
        this.createdAt = LocalDateTime.now();
    }
}
