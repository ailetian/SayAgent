package com.hify.hify.conversation.repository;

import com.hify.hify.common.base.BaseRepository;
import com.hify.hify.conversation.entity.Conversation;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 会话仓储（M6 T1）。
 *
 * <p>大白话：只写「按业务 id + 当前用户查会话」「列出某用户的会话（按最后活跃倒序）」「keyset 翻页」这类派生查询；
 * 通用增删改查由 {@link BaseRepository} 提供。@SQLRestriction 已自动过滤软删行，无需再拼 deleted。
 */
public interface ConversationRepository extends BaseRepository<Conversation> {

    /** 按业务 id + 归属用户查会话（同时完成归属校验）。 */
    Optional<Conversation> findByConversationIdAndUserId(String conversationId, Long userId);

    /** 按业务 id 查会话（忽略归属，仅用于存在性 + 所有权判定）。 */
    Optional<Conversation> findByConversationId(String conversationId);

    /** 列出某用户的全部会话，按最后活跃时间倒序。 */
    List<Conversation> findByUserIdOrderByLastActiveAtDesc(Long userId);

    /**
     * keyset 分页（§6.4）：取某用户 id 小于 {@code lastId} 的最近会话。
     * 调用方须传入 {@code Pageable} 且 {@code Sort.by(Sort.Direction.DESC, "id")}，禁止用 offset。
     */
    List<Conversation> findByUserIdAndIdLessThan(Long userId, Long lastId, Pageable pageable);
}
