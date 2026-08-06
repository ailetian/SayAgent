package com.hify.hify.conversation.web;

import java.time.Instant;

/**
 * 消息视图对象（M6 T1，对外只读，禁止直接序列化实体，§7.11 规则37）。
 */
public record ChatMessageVO(
        Long id,
        String conversationId,
        String role,
        String content,
        Integer seq,
        Instant createdAt,
        /** 调用轨迹 JSON（KB 检索 / MCP 工具调用明细），供前端事后回看。 */
        String traceJson
) {
}
