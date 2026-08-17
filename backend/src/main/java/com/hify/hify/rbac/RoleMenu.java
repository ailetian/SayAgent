package com.hify.hify.rbac;

import com.hify.hify.common.base.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

/**
 * 角色-菜单映射「关系行」（M9/T4，对应 V31 的 {@code role_menu} 表，种子数据）。
 *
 * <p>大白话：这一行表示「某个角色能看到某个菜单」。例如
 * {@code (ADMIN, chat)} 表示管理员能看到「对话」菜单。
 *
 * <p>表结构以已落库的 V31 为真相源：主键为代理 {@code id}(BIGINT 自增)，
 * 业务唯一键由 {@code (role_code, menu_code, deleted)} 保证（非复合主键，
 * 故不采用文档草稿里的 @IdClass，避免与真实 DDL 主键冲突，§3.2/验证纪律）。
 *
 * <p>四字段由 {@link BaseEntity} 提供；软删过滤本类显式声明（§6.1）。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SQLRestriction("deleted = 0")
@SQLDelete(sql = "UPDATE `role_menu` SET deleted = 1 WHERE id = ?")
@Entity
@Table(name = "role_menu")
public class RoleMenu extends BaseEntity {

    /** 角色编码 ADMIN/OPERATOR/USER（与 {@code UserRole} 同源，存字符串）。 */
    @Column(nullable = false, length = 20)
    private String roleCode;

    /** 菜单编码，对应 {@link MenuItem#code}。 */
    @Column(nullable = false, length = 50)
    private String menuCode;
}
