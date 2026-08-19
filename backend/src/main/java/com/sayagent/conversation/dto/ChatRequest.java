package com.sayagent.conversation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 聊天流式请求（M6 T2）。
 *
 * <p>大白话：前端把「用户这句话」和可选的「会话 id」发过来；不传 conversationId 就由服务端新建会话。
 */
public record ChatRequest(

        /** 会话 id（客户端生成的可选 UUID；不传则新建会话）。 */
        String conversationId,

        /** 用户消息（非空，最长 8000 字）。 */
        @NotBlank
        @Size(max = 8000)
        String message,

        /** 绑定的 Agent id（业务键或数据库 id；不传则走默认 Agent）。 */
        String agentId
) {
}
