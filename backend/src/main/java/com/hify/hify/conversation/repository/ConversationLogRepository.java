package com.hify.hify.conversation.repository;

import com.hify.hify.common.base.BaseRepository;
import com.hify.hify.conversation.entity.ConversationLog;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 对话日志仓储（M6 T1 / T4 落库）。
 *
 * <p>大白话：写日志（落库）由 T4 异步组件调用；查日志按用户倒序 keyset 分页（§6.4）。
 * 通用增删改查由 {@link BaseRepository} 提供。@SQLRestriction 已自动过滤软删行。
 */
public interface ConversationLogRepository extends BaseRepository<ConversationLog> {

    /** 按用户倒序取最近日志（keyset 分页：调用方用末条 id 续拉，禁 offset，§6.4）。 */
    List<ConversationLog> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);
}
