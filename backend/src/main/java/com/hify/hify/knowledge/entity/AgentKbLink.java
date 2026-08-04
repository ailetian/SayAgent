package com.hify.hify.knowledge.entity;

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
 * Agent ↔ 知识库 多对多挂载关系（K1/K7）。
 *
 * <p>大白话：这张表记录「哪个 Agent 挂了哪些知识库」。用户提问时只在这些挂载的库里检索，
 * 没挂载的库对该 Agent 完全不可见（权限委托 Agent 挂载，§3.5）。软删除同其它业务表（§6.1）。
 */
@Entity
@Table(name = "agent_kb_link")
@SQLRestriction("deleted = 0")
@SQLDelete(sql = "UPDATE `agent_kb_link` SET deleted = 1 WHERE id = ?")
@Getter
@Setter
@NoArgsConstructor
public class AgentKbLink extends BaseEntity {

    /** 挂载的 Agent id（agent.id）。 */
    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    /** 挂载的知识库 id（knowledge_base.id）。 */
    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    /** 挂载操作人（username，仅审计用）。 */
    @Column(name = "created_by", length = 64)
    private String createdBy;
}
