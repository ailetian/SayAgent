package com.hify.hify.conversation.repository;

import com.hify.hify.conversation.entity.MessageFeedback;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 回答反馈仓储（K0808 T7）。
 *
 * <p>大白话：写反馈用 {@link #upsert}（同一用户对同消息重复点 = 覆盖）；查「热门被踩」用
 * {@link #topThumbsDownKb} / {@link #topThumbsDownAgent}（按 kb_id / agent_id 聚合被踩次数 TOP-N）。
 *
 * <p>实体 {@link MessageFeedback} 不继承 {@code BaseEntity}（本表无 {@code deleted}），故此处直接
 * {@code extends JpaRepository}，而非项目的 {@code BaseRepository<T extends BaseEntity>}。
 *
 * <p>Repository 只做数据访问，业务判断（该赞还是该踩、要不要记 reason、阈值告警）放 Service（§3.2）。
 */
public interface MessageFeedbackRepository extends JpaRepository<MessageFeedback, Long> {

    /**
     * 对同一 (message_id, user_id) 覆盖写反馈（upsert）。
     * 首次评价 INSERT，重复评价走唯一键 {@code uk_message_user} 触发 ON DUPLICATE KEY UPDATE 覆盖。
     *
     * <p><b>参数为 {@code String} 而非枚举</b>：本方法是 native query，Hibernate 对 native query 的枚举参数
     * 默认按 ordinal(0/1) 绑定（§7.2 禁魔法数字、必须存枚举字符串 'THUMBS_UP'/'THUMBS_DOWN'），故调用方须传
     * {@code rating.name()}（如 T8 的 MessageFeedbackService 在转换 DTO 后传入），避免把枚举存成 '0'/'1'。
     *
     * @return 受影响行数（INSERT=1，UPDATE=2）
     */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO message_feedback (message_id, user_id, agent_id, kb_id, rating, reason, created_at, updated_at)
            VALUES (:messageId, :userId, :agentId, :kbId, :rating, :reason, NOW(3), NOW(3))
            ON DUPLICATE KEY UPDATE
                agent_id = :agentId,
                kb_id = :kbId,
                rating = :rating,
                reason = :reason,
                updated_at = NOW(3)
            """, nativeQuery = true)
    int upsert(@Param("messageId") Long messageId,
               @Param("userId") Long userId,
               @Param("agentId") String agentId,
               @Param("kbId") Long kbId,
               @Param("rating") String rating,
               @Param("reason") String reason);

    /**
     * 按 kb_id 聚合「被踩(THUMBS_DOWN)」次数 TOP-N（热门被踩知识库，T13 健康报告用）。
     * 返回每行 {@code [kb_id, count]}，按 count 倒序。
     */
    @Query(value = """
            SELECT kb_id, COUNT(*) AS cnt
            FROM message_feedback
            WHERE rating = 'THUMBS_DOWN' AND kb_id IS NOT NULL
            GROUP BY kb_id
            ORDER BY cnt DESC
            LIMIT :n
            """, nativeQuery = true)
    List<Object[]> topThumbsDownKb(@Param("n") int n);

    /**
     * 按 agent_id 聚合「被踩(THUMBS_DOWN)」次数 TOP-N（热门被踩 Agent）。
     * 返回每行 {@code [agent_id, count]}，按 count 倒序。
     */
    @Query(value = """
            SELECT agent_id, COUNT(*) AS cnt
            FROM message_feedback
            WHERE rating = 'THUMBS_DOWN' AND agent_id IS NOT NULL
            GROUP BY agent_id
            ORDER BY cnt DESC
            LIMIT :n
            """, nativeQuery = true)
    List<Object[]> topThumbsDownAgent(@Param("n") int n);

    /**
     * 取消评价：删除 (message_id, user_id) 的唯一行。
     * 本表 {@code rating} 为 NOT NULL（见 V28），无法以「置空」表达取消，故用删除覆盖行实现。
     * 删除走 Spring Data 派生（本实体无 {@code @SQLRestriction}，不会误带 deleted 条件）。
     */
    @Transactional
    void deleteByMessageIdAndUserId(Long messageId, Long userId);

    /**
     * 按筛选条件聚合「被踩(THUMBS_DOWN)」最多的消息 TOP-N（T8 管理员视图）。
     * 返回每行 {@code [message_id, count]}，按 count 倒序。
     * {@code kbId}/{@code agentId} 为可空筛选；传 null 表示不限该维度。
     */
    @Query(value = """
            SELECT message_id, COUNT(*) AS cnt
            FROM message_feedback
            WHERE rating = 'THUMBS_DOWN'
              AND (:kbId IS NULL OR kb_id = :kbId)
              AND (:agentId IS NULL OR agent_id = :agentId)
            GROUP BY message_id
            ORDER BY cnt DESC
            LIMIT :n
            """, nativeQuery = true)
    List<Object[]> topThumbsDownMessages(@Param("kbId") Long kbId,
                                         @Param("agentId") String agentId,
                                         @Param("n") int n);

    /**
     * 按筛选条件聚合「踩的原因」分布 TOP-N（T8 管理员视图）。
     * 返回每行 {@code [reason, count]}，按 count 倒序；原因空值不参与统计。
     */
    @Query(value = """
            SELECT reason, COUNT(*) AS cnt
            FROM message_feedback
            WHERE rating = 'THUMBS_DOWN'
              AND reason IS NOT NULL
              AND (:kbId IS NULL OR kb_id = :kbId)
              AND (:agentId IS NULL OR agent_id = :agentId)
            GROUP BY reason
            ORDER BY cnt DESC
            LIMIT :n
            """, nativeQuery = true)
    List<Object[]> reasonDistribution(@Param("kbId") Long kbId,
                                      @Param("agentId") String agentId,
                                      @Param("n") int n);

    /**
     * 当前用户对自己若干消息的反馈（T9 前端回显用）。
     * 返回行按 message_id 升序，调用方自行按 {@code messageId} 取 rating。
     */
    List<MessageFeedback> findByUserIdAndMessageIdIn(Long userId, List<Long> messageIds);
}
