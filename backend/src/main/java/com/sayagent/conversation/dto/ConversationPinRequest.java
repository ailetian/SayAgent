package com.sayagent.conversation.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 会话置顶 / 取消置顶请求（M6 补充）。
 */
public record ConversationPinRequest(
        @NotNull(message = "pinned 不能为空") Boolean pinned
) {
}
