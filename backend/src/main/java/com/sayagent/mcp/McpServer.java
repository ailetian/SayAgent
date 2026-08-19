package com.sayagent.mcp;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.sayagent.common.base.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

/**
 * MCP Server 配置实体（M7/T1，§3.2 后端包结构）。
 *
 * <p>大白话：一张「内部系统通讯录」——管理员登记某个内部 MCP 系统的名字、地址、连接方式(type)、
 * 以及是否启用(status)。它只存「连接配置」，真正的连接/工具发现/调用是 T2 的事。
 *
 * <p>软删除：必须在本 {@code @Entity} 上显式声明 {@code @SQLRestriction} 与 {@code @SQLDelete}
 * （{@code BaseEntity} 的 {@code @MappedSuperclass} 注解不会传播到子类，见 §6.1 实现纪律）；
 * {@code DELETE /api/mcp/servers/{id}} 走软删除（置 {@code deleted=1}），即「停用」而非真删。
 */
@Entity
@Table(name = "mcp_server")
@SQLRestriction("deleted = 0")
@SQLDelete(sql = "UPDATE `mcp_server` SET deleted = 1 WHERE id = ?")
@Getter
@Setter
@NoArgsConstructor
public class McpServer extends BaseEntity {

    /** MCP Server 名称（如「订单系统」）。 */
    @Column(nullable = false, length = 64)
    private String name;

    /** MCP Server 地址（内部服务地址，非秘钥，可返回前端，§7.11 规则37）。 */
    @Column(nullable = false, length = 255)
    private String address;

    /** 连接方式：STDIO / SSE / HTTP（§2 模块8 轻量化 MCP）。 */
    @Column(nullable = false, length = 20, columnDefinition = "varchar(20) default 'STDIO'")
    private String type;

    /** 启用状态：1=启用，0=停用。 */
    @Column(nullable = false, columnDefinition = "tinyint default 1")
    private Integer status;

    /**
     * 鉴权类型：NONE(免鉴权) / BEARER(Authorization: Bearer) / APIKEY(Authorization: ApiKey) / HEADER(自定义头)。
     * 默认 NONE（M10/T1）。MCP 连接时由 {@code McpClientManager} 据此把 {@code authConfig} 注入请求头。
     */
    @Column(name = "auth_type", nullable = false, length = 20, columnDefinition = "varchar(20) default 'NONE'")
    private String authType;

    /**
     * 鉴权凭据 JSON（敏感）：如 {@code {"token":"..."}} / {@code {"key":"..."}} / {@code {"headers":{"X-Api-Key":"..."}}}。
     * <b>敏感字段：必须 {@code @JsonIgnore}，绝不序列化进任何对外响应（§7.11 规则37）；注入请求头时也禁止打日志（§7.4 规则19）。</b>
     */
    @JsonIgnore
    @Column(name = "auth_config", length = 4096)
    private String authConfig;

    /**
     * 数据敏感度标签（M10/T4，§2.1 授权知情）：PUBLIC/INTERNAL/CONFIDENTIAL/FINANCE_HR。
     * 管理员注册 MCP 时人工标注（MCP 协议无标准敏感度字段）。属「分类标签」<b>非秘钥</b>，
     * 可随 {@link McpServerVO} 返前端展示（与 {@code authConfig} 不同，§7.11 规则37）。
     * 列由 T1 的 V32 建好，本字段仅做实体映射（禁止再写迁移，§9）。
     */
    @Column(name = "data_sensitivity", nullable = false, length = 20, columnDefinition = "varchar(20) default 'INTERNAL'")
    private String dataSensitivity;
}
