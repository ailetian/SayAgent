package com.hify.hify.conversation.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 会话重命名请求（M6 补充）。
 */
public record ConversationRenameRequest(
        @NotBlank(message = "标题不能为空") String title
) {
}
