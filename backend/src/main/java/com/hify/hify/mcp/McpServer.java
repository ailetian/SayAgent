package com.hify.hify.mcp;

import com.hify.hify.common.base.BaseEntity;
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
}
