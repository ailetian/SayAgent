package com.sayagent.conversation.repository;

import com.sayagent.common.base.BaseRepository;
import com.sayagent.conversation.entity.Message;

import java.util.List;

import org.springframework.data.domain.Pageable;

/**
 * 消息仓储（M6 T1）。
 *
 * <p>大白话：只写「按会话 id 顺序取消息」「数消息条数」「keyset 翻页」这类派生查询；
 * 通用增删改查由 {@link BaseRepository} 提供。JPA 标识为单 id（§6.3 复合主键的物理约束在 DB 层达成，
 * Hibernate 不支持 IDENTITY+@IdClass 共存，详见 Message 类注释）。查询都带 conversation_id
 * （禁止裸查全表，§6.2 规则2）。
 */
public interface MessageRepository extends BaseRepository<Message> {

    /** 按会话 id 顺序取全部消息（用于还原时间线）。 */
    List<Message> findByConversationIdOrderBySeqAsc(String conversationId);

    /** 数某个会话的消息条数（用于乐观计数回填）。 */
    long countByConversationId(String conversationId);

    /** keyset 首页（lastId 为空）：取某会话最新 N 条（id DESC），命中 {@code idx_conv_created}（§6.4）。 */
    List<Message> findTop20ByConversationIdOrderByIdDesc(String conversationId);

    /**
     * keyset 分页（§6.4）：取某会话中 id 小于 {@code lastId} 的最近消息。
     * 调用方须传入 {@code Pageable} 且 {@code Sort.by(Sort.Direction.DESC, "id")}，禁止用 offset。
     */
    List<Message> findByConversationIdAndIdLessThan(String conversationId, Long lastId, Pageable pageable);
}
