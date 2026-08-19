package com.sayagent.conversation.entity;

import com.sayagent.common.base.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

/**
 * 对话日志（M6 T1）。
 *
 * <p>大白话：每次对话的流水账——谁、何时、用哪个 Agent、问了啥、花多少 token、是否降级。
 * 写多读少（§6.2 规则4），故索引仅 PK + idx_created_at + idx_user_id 三个。
 * 四字段 id/created_at/updated_at/deleted 由 BaseEntity 提供（§6.1）；
 * 软删过滤由本类 {@code @SQLRestriction}/{@code @SQLDelete} 显式实现（坑位5：不可放 BaseEntity）。
 */
@Entity
@Table(name = "conversation_log", indexes = {
        @Index(name = "idx_created_at", columnList = "created_at"),
        @Index(name = "idx_user_id", columnList = "user_id, deleted")
})
@SQLRestriction("deleted = 0")
@SQLDelete(sql = "UPDATE `conversation_log` SET deleted = 1 WHERE id = ?")
@Getter
@Setter
@NoArgsConstructor
public class ConversationLog extends BaseEntity {

    /** 谁问的（外键→user.id）。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 用的哪个 Agent（→conversation.agent_id 逻辑外键，可选）。 */
    @Column(name = "agent_id", length = 50)
    private String agentId;

    /** 所属会话（→conversation.conversation_id 逻辑外键，可选）。 */
    @Column(name = "conversation_id", length = 50)
    private String conversationId;

    /** 用户问题（MEDIUMTEXT，不索引）。 */
    @Column(columnDefinition = "MEDIUMTEXT", nullable = false)
    private String question;

    /** 输入 token（§4.9）。 */
    @Column(name = "in_tok")
    private Integer inTok = 0;

    /** 输出 token（§4.9）。 */
    @Column(name = "out_tok")
    private Integer outTok = 0;

    /** 实际命中厂商（§4.9）。 */
    @Column(name = "provider", length = 32)
    private String provider;

    /** 实际模型（§4.9）。 */
    @Column(name = "model", length = 64)
    private String model;

    /** 是否走降级（§4.9）。 */
    @Column(name = "fallback", nullable = false)
    private Boolean fallback = false;
}
