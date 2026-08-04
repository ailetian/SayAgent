package com.hify.hify.conversation.entity;

import com.hify.hify.common.base.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

/**
 * 消息（M6 T1 / T2）。
 *
 * <p>大白话：一条对话里的「一句话」——谁说的（role）、说了啥（content）、第几句（seq）、状态（status）。
 *
 * <p>§6.3 强制：大表预判 → RANGE 分区。分区键必须是 {@code created_at}，故<b>数据库主键为复合键
 * (id, created_at)</b>（见 V15 迁移）。四字段 + 软删由 {@link BaseEntity} 提供；
 * 软删过滤仍逐实体 {@code @SQLRestriction}/{@code @SQLDelete} 实现（坑位5：不可放 BaseEntity）。
 *
 * <p>JPA 标识说明：Hibernate 不支持「复合 @IdClass + IDENTITY 自增」共存（自增列 id 与分区键
 * created_at 无法同时由 @IdClass 表达），而计划 DDL 又要求 id 为 AUTO_INCREMENT。二者在
 * Hibernate/MySQL 下互斥。为此 JPA 层仅以 {@code id} 为 @Id（@IdClass 省略），而<b>数据库物理主键
 * 仍为 (id, created_at)</b>，分区要求完全满足；@SQLDelete 以全局唯一的 id 定位单行
 * （{@code WHERE id = ?}），不依赖 created_at。这是规范内部矛盾下的最优解（§6.3 物理约束达成）。
 * 二级索引仅保留 §6.3 要求的 {@code idx_conv_created} 与 T2 历史回放用的 {@code idx_conversation_seq}
 * （created_at 已由分区主键覆盖，不再另建 idx_created_at，§6.3）。
 */
@Entity
@Table(name = "message", indexes = {
        @Index(name = "idx_conv_created", columnList = "conversation_id, deleted, created_at"),
        @Index(name = "idx_conversation_seq", columnList = "conversation_id, deleted, seq")
})
@SQLRestriction("deleted = 0")
@SQLDelete(sql = "UPDATE `message` SET deleted = 1 WHERE id = ?")
@Getter
@Setter
@NoArgsConstructor
public class Message extends BaseEntity {

    /** 所属会话（→conversation.conversation_id 逻辑外键，VARCHAR(50) 业务键）。 */
    @Column(name = "conversation_id", nullable = false, length = 50)
    private String conversationId;

    /** 归属用户（冗余，便于按用户检索，→user.id）。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** user / assistant。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private MessageRole role;

    /** 文本（MEDIUMTEXT，不索引，§6.2 规则7）。 */
    @Column(columnDefinition = "MEDIUMTEXT", nullable = false)
    private String content;

    /** 会话内序号（从 1 递增，T2 历史回放排序用）。 */
    @Column(nullable = false)
    private Integer seq = 0;

    /** SENT / PENDING / FAILED（T2 流式生命周期用）。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private MessageStatus status;

    /** 命中厂商（OPENAI/OLLAMA 等，§4.9 可观测；user 消息此列留空）。 */
    @Column(name = "provider", length = 32)
    private String provider;

    /** 本消息消耗的输出 token（§4.9；user 消息为输入侧估算，可空）。 */
    @Column(name = "tokens")
    private Integer tokens;

    /** 角色：用户 / 助手。 */
    public enum MessageRole {
        USER, ASSISTANT
    }

    /** 消息状态（T2 流式生命周期：先 PENDING 占位，结束后置 SENT / FAILED）。 */
    public enum MessageStatus {
        PENDING, SENT, FAILED
    }
}
