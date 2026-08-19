package com.sayagent.rbac;

import com.sayagent.common.base.BaseEntity;
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
 * 侧边栏菜单定义「户口簿」（M9/T4，对应 V31 的 {@code menu_item} 表，种子数据）。
 *
 * <p>大白话：这是左侧菜单的「总目录」——一项一行（如对话/智能体/知识库…），
 * 含编码、显示名、前端路由、图标、排序。具体谁能看到哪一项，由 {@link RoleMenu} 决定。
 *
 * <p>四字段（id/createdAt/updatedAt/deleted）由 {@link BaseEntity} 提供；软删过滤本类显式声明（§6.1）。
 * 本实体属 {@code rbac} 包自包含，不反向依赖任何业务包（§3.2 跨模块纪律）。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SQLRestriction("deleted = 0")
@SQLDelete(sql = "UPDATE `menu_item` SET deleted = 1 WHERE id = ?")
@Entity
@Table(name = "menu_item")
public class MenuItem extends BaseEntity {

    /** 菜单编码（唯一，前端路由 meta 映射键，如 chat/agents）。 */
    @Column(nullable = false, length = 50)
    private String code;

    /** 菜单显示名（如「对话」）。 */
    @Column(nullable = false, length = 64)
    private String name;

    /** 前端路由路径（如 /chat）。 */
    @Column(nullable = false, length = 100)
    private String path;

    /** 图标名（如 chat/bot），可空。 */
    @Column(length = 50)
    private String icon;

    /** 排序权重，越小越靠前。 */
    @Column(nullable = false)
    private int sort;
}
