package com.sayagent.modelprovider.client;

import com.sayagent.common.tool.ToolCall;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * 大模型统一响应（§3.5 强类型 DTO，禁止裸 Map/JSONObject）。
 *
 * <p>大白话：不管哪家厂商返回的格式多花哨，最终都收拢成这 5 个字段，路由层和上层只认它。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmResponse {

    /** 模型回复正文。 */
    private String content;

    /** 结束原因（如 stop / length / tool_calls）。 */
    private String finishReason;

    /** 提示词消耗的 token 数（上游未返回则为 null）。 */
    private Integer promptTokens;

    /** 回复消耗的 token 数（上游未返回则为 null）。 */
    private Integer completionTokens;

    /** 上游原始 HTTP 状态码，便于排查。 */
    private Integer rawStatus;

    /** 模型回传的工具调用意图列表（M8/T2 函数调用）；无工具调用时为 null。 */
    private List<ToolCall> toolCalls;
}
