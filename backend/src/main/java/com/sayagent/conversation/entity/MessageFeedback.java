package com.sayagent.conversation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 回答点踩/点赞反馈（K0808 T7）。
 *
 * <p>大白话：用户对某条 AI 回答「赞 👍 / 踩 👎」后留下的态度记录。
 * 同一用户对同一条消息<b>只保留最新态度</b>（upsert 覆盖写），所以本表<b>不引入软删 {@code deleted}</b>——
 * 这与 {@code BaseEntity} 的 {@code deleted} 不同，是有意取舍（覆盖写即可，无需「删了留历史」），详见 V28 迁移注释。
 *
 * <p>因不继承 {@link com.sayagent.common.base.BaseEntity}（否则 Hibernate 会去插不存在的 {@code deleted} 列），
 * 这里手写 {@code id}/{@code createdAt}/{@code updatedAt} 三个通用字段，并用 {@code @PrePersist}/@PreUpdate 维护时间戳。
 *
 * <p>{@code rating} 用枚举字符串（§7.2 禁魔法数字），不存字面量 0/1。
 */
@Entity
@Table(name = "message_feedback", indexes = {
        @Index(name = "uk_message_user", columnList = "message_id, user_id", unique = true),
        @Index(name = "idx_created_at", columnList = "created_at")
})
@Getter
@Setter
@NoArgsConstructor
public class MessageFeedback {

    /** 主键（自增）。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 被评价的消息 id（→message.id）。 */
    @Column(name = "message_id", nullable = false)
    private Long messageId;

    /** 评价者用户 id（→user.id）。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 评价所用 Agent（→conversation.agent_id 逻辑外键，可空）。 */
    @Column(name = "agent_id", length = 50)
    private String agentId;

    /** 评价参照的知识库（→knowledge_base.id，可空，便于按库聚合被踩）。 */
    @Column(name = "kb_id")
    private Long kbId;

    /** 评价：赞 / 踩（枚举字符串，禁魔法数字，§7.2）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "rating", nullable = false, length = 16)
    private Rating rating;

    /** 踩的原因（仅 THUMBS_DOWN 时填，赞时 NULL）。 */
    @Column(name = "reason", length = 512)
    private String reason;

    /** 创建时间（插入后不可改）。 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间（每次保存刷新）。 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** 评价类型：赞 / 踩（§7.2 禁魔法数字，存字符串枚举）。 */
    public enum Rating {
        THUMBS_UP, THUMBS_DOWN
    }

    /** 写入前补创建/更新时间（JPA 不会自动维护时间戳）。 */
    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    /** 更新前刷新更新时间。 */
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
