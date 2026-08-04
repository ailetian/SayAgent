package com.hify.hify.conversation.web;

/**
 * SSE 增量事件（M6/T2 流式回显契约：data: {"role":"assistant","delta":"..."}）。
 *
 * <p>大白话：每推一片 token 就发一个这样的 JSON；流结束再发一个 {@code data: [DONE]}。
 * 本 DTO 仅描述 token 增量事件，[DONE] 由 Controller/Service 直接发原始标记。
 */
public record ChatDeltaEvent(
        String role,
        String delta
) {
}
