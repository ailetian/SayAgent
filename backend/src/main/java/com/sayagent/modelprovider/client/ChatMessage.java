package com.sayagent.modelprovider.client;

import com.sayagent.common.tool.ToolCall;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * 单条对话消息（§3.5 强类型 DTO）。
 *
 * <p>大白话：一轮对话（谁说的、说了啥）。各 Client 负责把它翻译成厂商认识的 role 文案
 * （如 Gemini 把 assistant 翻成 model）。
 *
 * <p>M8/T2 扩展：assistant 可携带 {@code toolCalls}（模型决定要调哪些工具）、tool 角色消息
 * 携带 {@code toolCallId}（对应被调用的那个工具小票）、{@code name} 为可选标注。
 */
@Getter
@Setter
@NoArgsConstructor
public class ChatMessage {

    /** 角色：user / assistant / system / tool（由各 Client 映射到厂商格式）。 */
    private String role;

    /** 消息正文（tool 角色下为工具执行结果；assistant 带 tool_calls 时可为 null）。 */
    private String content;

    /** assistant 消息里模型决定要调用的工具列表（M8/T2 函数调用）。 */
    private List<ToolCall> toolCalls;

    /** tool 角色消息必带：对应它要回应的那个 tool_calls 条目的 id（M8/T2）。 */
    private String toolCallId;

    /** 可选标注（厂商协议里的 name 字段，如 user/assistant 的可选昵称）。 */
    private String name;

    /** 保底两参构造器：维持历史 {@code new ChatMessage(role, content)} 调用不变（避免 Lombok @AllArgsConstructor 增参破坏旧调用）。 */
    public ChatMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }
}
