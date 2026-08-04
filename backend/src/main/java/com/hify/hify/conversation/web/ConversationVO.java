package com.hify.hify.conversation.web;

import java.time.Instant;

/**
 * 会话视图对象（M6 T1，对外只读，禁止直接序列化实体，§7.11 规则37）。
 */
public record ConversationVO(
        String conversationId,
        String title,
        String agentId,
        Long messageCount,
        String status,
        Boolean pinned,
        Instant lastActiveAt,
        Instant createdAt
) {
}
