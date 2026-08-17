package com.hify.hify.rbac;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 统一资源授权行（M9/T5，§2.1 资源授权混合模型）。
 *
 * <p>大白话：这是一张「谁能看/用哪个知识库或 Agent」的授权表。
 * 一行 = 「某个主体（角色或具体用户）对某个资源（KB/AGENT）的读/写/用/编四权」。
 * 资源可见性判定 = 资源 {@code visibility='PUBLIC'} <b>或</b> 存在匹配当前用户的 {@code resource_access} 行
 * <b>或</b> 当前用户是 ADMIN（兜底）。
 *
 * <p><b>关键约束（P2-8）</b>：本实体<b>不继承 {@code BaseEntity}</b>——{@code resource_access} 表
 * <b>没有</b> {@code deleted}/{@code updated_at} 列，只有 {@code created_at}。若继承 BaseEntity 会多出
 * Hibernate 找不到的 {@code deleted} 列而启动失败。故本类独立 {@code @Entity}，自建 {@code created_at}。
 * 也因此<b>没有</b> {@code @SQLRestriction}/{@code @SQLDelete}（无软删）。
 *
 * <p>列名与 {@code V31__m9_user_role_and_access.sql} 的 {@code resource_access} DDL 严格一一对应。
 */
@Entity
@Table(
        name = "resource_access",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_principal_resource",
                columnNames = {"principal_type", "principal_id", "resource_type", "resource_id"})
)
@Getter
@Setter
@NoArgsConstructor
public class ResourceAccess {

    /** 主键：BIGINT 自增（与 DDL 一致，非 UUID，防索引碎片 §6.1）。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 授权主体类型：ROLE（角色基线）/ USER（个人覆盖）。VARCHAR(20)，非 ENUM（P2-7 可扩展）。 */
    @Column(name = "principal_type", nullable = false, length = 20)
    private String principalType;

    /**
     * 授权主体 id：ROLE=角色名(ADMIN/OPERATOR/USER)；USER=登录名 username（VARCHAR(64)）。
     * 与 {@code knowledge_base.creator_id} / {@code agent.created_by} 同源（P0-2），<b>不存数字 id</b>。
     */
    @Column(name = "principal_id", nullable = false, length = 64)
    private String principalId;

    /** 资源类型：KB / AGENT（可扩展，不用 ENUM，P2-7）。 */
    @Column(name = "resource_type", nullable = false, length = 20)
    private String resourceType;

    /** 资源 id：knowledge_base.id / agent.id。 */
    @Column(name = "resource_id", nullable = false)
    private Long resourceId;

    /** 可读。 */
    @Column(name = "can_read", nullable = false, columnDefinition = "tinyint(1) default 1")
    private Boolean canRead = true;

    /** 可写。 */
    @Column(name = "can_write", nullable = false, columnDefinition = "tinyint(1) default 0")
    private Boolean canWrite = false;

    /** 可用（对话/调用）。 */
    @Column(name = "can_use", nullable = false, columnDefinition = "tinyint(1) default 0")
    private Boolean canUse = false;

    /** 可编辑配置。 */
    @Column(name = "can_edit", nullable = false, columnDefinition = "tinyint(1) default 0")
    private Boolean canEdit = false;

    /** 创建时间（本表无 updated_at，故仅 created_at）。 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /**
     * 全权构造（创建者自授权用）：四个 can_* 全置 1。
     *
     * @param principalType 主体类型 ROLE/USER
     * @param principalId   主体 id（角色名或登录名）
     * @param resourceType  资源类型 KB/AGENT
     * @param resourceId    资源 id
     */
    public ResourceAccess(String principalType, String principalId, String resourceType, Long resourceId) {
        this.principalType = principalType;
        this.principalId = principalId;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.canRead = true;
        this.canWrite = true;
        this.canUse = true;
        this.canEdit = true;
        this.createdAt = LocalDateTime.now();
    }
}
