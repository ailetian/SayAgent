package com.hify.hify.knowledge.web;

import com.hify.hify.modelprovider.client.ChatMessage;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 问答请求（K8 {@code POST /{kbId}/ask}）。
 *
 * <p>大白话：把「问题」发过来，可附带对话历史（多轮指代消解用）；答案由 K5 编排（阈值拒答 + 溯源）。
 * {@code history} 直接复用 M3 的 {@link ChatMessage}（role/content），不在此重复造轮子。
 */
public record AskRequest(

        @NotBlank(message = "query 不能为空")
        String query,

        /** 对话历史（可空，多轮场景用于指代消解）。 */
        List<ChatMessage> history
) {
}
