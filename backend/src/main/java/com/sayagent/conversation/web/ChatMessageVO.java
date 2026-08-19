package com.sayagent.conversation.web;

import java.time.Instant;

/**
 * 消息视图对象（M6 T1，对外只读，禁止直接序列化实体，§7.11 规则37）。
 *
 * <p>K0808 T11 扩展：透传 token 用量与厂商/模型（§4.9 可观测），以及当前用户对该消息的评分回显
 * （myRating 由前端经 listFeedback 批量回灌，后端默认 null；§7.11 敏感字段不在此放秘钥）。
 */
public record ChatMessageVO(
        Long id,
        String conversationId,
        String role,
        String content,
        Integer seq,
        Instant createdAt,
        /** 调用轨迹 JSON（KB 检索 / MCP 工具调用明细），供前端事后回看。 */
        String traceJson,
        /** 输入 token 用量（K0808 T10；来自 message.tokens_in，老消息为 null）。 */
        Integer tokensIn,
        /** 输出 token 用量（复用 message.tokens）。 */
        Integer tokensOut,
        /** 命中厂商（复用 message.provider；user 消息为 null）。 */
        String provider,
        /** 实际模型名（K0808 T11；来自 message.model，user 消息为 null）。 */
        String model,
        /** 当前用户对该消息的评分回显（'THUMBS_UP'/'THUMBS_DOWN'/'', 前端 listFeedback 填充）。 */
        String myRating
) {
}
