package com.hify.hify.conversation.entity;

import com.hify.hify.common.base.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;

/**
 * 对话会话（M6 T1）。
 *
 * <p>大白话：一次连续对话就是一条 conversation；它挂所属用户、标题、消息数、最后活跃时间、状态。
 * 四字段 id/created_at/updated_at/deleted 由 BaseEntity 提供（§6.1）；
 * 软删过滤由本类 {@code @SQLRestriction}/{@code @SQLDelete} 显式实现（坑位5：不可放 BaseEntity）。
 */
@Entity
@Table(name = "conversation", uniqueConstraints = {
        @UniqueConstraint(name = "uk_conversation_id", columnNames = {"conversation_id", "deleted"})
}, indexes = {
        @Index(name = "idx_user_last_active", columnList = "user_id, deleted, last_active_at"),
        @Index(name = "idx_agent_id", columnList = "agent_id, deleted"),
        @Index(name = "idx_created_at", columnList = "created_at")
})
@SQLRestriction("deleted = 0")
@SQLDelete(sql = "UPDATE `conversation` SET deleted = 1 WHERE id = ?")
@Getter
@Setter
@NoArgsConstructor
public class Conversation extends BaseEntity {

    /** 客户端生成的可选 UUID，业务主键（对外暴露）。 */
    @Column(name = "conversation_id", nullable = false, length = 50)
    private String conversationId;

    /** 所属用户（外键→user.id）。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 会话标题（首条消息前 20 字）。 */
    @Column(nullable = false, length = 200)
    private String title = "";

    /** 绑定的 Agent id（可选）。 */
    @Column(name = "agent_id", length = 50)
    private String agentId;

    /** 消息条数（乐观计数）。 */
    @Column(name = "message_count", nullable = false)
    private Long messageCount = 0L;

    /** 最后活跃时间。 */
    @Column(name = "last_active_at", nullable = false)
    private Instant lastActiveAt;

    /** 会话状态（tinyint 0=ACTIVE 1=ARCHIVED，序号与库对齐）。 */
    @Enumerated(EnumType.ORDINAL)
    @Column(nullable = false)
    private ConversationStatus status = ConversationStatus.ACTIVE;

    /** 是否置顶（1=置顶，0=否；置顶会话在列表中排在最前）。 */
    @Column(name = "pinned", nullable = false, columnDefinition = "tinyint(1) default 0")
    private Boolean pinned = false;

    /** 会话状态枚举（序号与库 tinyint 对齐：ACTIVE=0 ARCHIVED=1）。 */
    public enum ConversationStatus {
        ACTIVE, ARCHIVED
    }
}
