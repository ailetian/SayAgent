package com.hify.hify.mcp.dto;

/**
 * MCP 工具调用结果（M7/T3，§7.11 规则38 安全规约）。
 *
 * <p>大白话：给 conversation 用的「外援战报」——成功就带 {@code result} 文本；失败就
 * {@code fallback=true} 带 {@code errorMessage}，但绝不携带 server 地址/秘钥等敏感凭证
 * （§7.4 规则19）。conversation 据此决定拼回还是降级提示。
 */
public record McpToolCallResult(
        boolean success,
        boolean fallback,
        Long serverId,
        String toolName,
        String result,
        String errorMessage) {

    /** 成功结果。 */
    public static McpToolCallResult success(Long serverId, String toolName, String result) {
        return new McpToolCallResult(true, false, serverId, toolName,
                result == null ? "" : result, null);
    }

    /** 失败降级（绝不向上抛）。 */
    public static McpToolCallResult fallback(Long serverId, String toolName, String errorMessage) {
        return new McpToolCallResult(false, true, serverId, toolName, null, errorMessage);
    }
}
