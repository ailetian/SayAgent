package com.hify.hify.knowledge.entity;

import com.hify.hify.common.base.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

/**
 * 知识库访问授权（RBAC，M5 整改扩展）。
 *
 * <p>大白话：一条记录 =「知识库(kb_id) 对 某角色/某个人(target) 开放访问权」。
 * 创建者(creator_id)在首次上传时自动获得一条 {@code USER} 授权；管理员可额外分配。
 * 访问判权统一看这张表 + 管理员身份，不再有「owner 私有」的特殊分支（语义收敛到一处）。
 */
@Entity
@Table(name = "kb_access")
@SQLRestriction("deleted = 0")
@SQLDelete(sql = "UPDATE `kb_access` SET deleted = 1 WHERE id = ?")
@Getter
@Setter
@NoArgsConstructor
public class KbAccess extends BaseEntity {

    /** 知识库 id（knowledge_base.id）。 */
    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    /** 授权目标类型：ROLE=角色，USER=具体人。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 20)
    private KbAccessTargetType targetType;

    /** 授权目标：target_type=ROLE 时为角色名(USER/ADMIN/...)，=USER 时为登录名 username。 */
    @Column(name = "target_id", nullable = false, length = 64)
    private String targetId;
}
