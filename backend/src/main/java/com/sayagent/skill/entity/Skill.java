package com.sayagent.skill.entity;

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
 * 技能实体（M8/T4，提示词式，§3.2 skill 业务包 / §6.1 软删纪律）。
 *
 * <p>大白话：技能 = 一段能增删查改的「提示词/指令」文本，被 Agent 挂载后拼进该 Agent 的人设
 * （系统提示词），对所有对话生效。库初始为空，按需把通用提示词写进去；同一条 skill 可被多个
 * Agent 引用（多对多）。与 MCP（执行动作）/知识库（喂上下文）正交——三者不抢地盘。
 *
 * <p>软删除：必须在本 {@code @Entity} 显式声明 {@code @SQLRestriction} 与 {@code @SQLDelete}
 * （{@code BaseEntity} 的 {@code @MappedSuperclass} 注解不传播到子类，见 §6.1 实现纪律）。
 */
@Entity
@Table(name = "skill")
@SQLRestriction("deleted = 0")
@SQLDelete(sql = "UPDATE `skill` SET deleted = 1 WHERE id = ?")
@Getter
@Setter
@NoArgsConstructor
public class Skill extends BaseEntity {

    /** 技能名称（唯一，给人看 + 管理用）。 */
    @Column(nullable = false, length = 100)
    private String name;

    /** 说明（给人看，不参与拼装）。 */
    @Column(length = 255)
    private String description;

    /** 提示词正文（核心字段）：被挂载时拼进 Agent 人设。 */
    @Column(nullable = false, columnDefinition = "text")
    private String promptText;

    @Column(nullable = false, columnDefinition = "tinyint(1) default 1")
    private Boolean enabled;
}
