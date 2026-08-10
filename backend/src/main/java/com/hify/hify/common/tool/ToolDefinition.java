package com.hify.hify.common.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 工具定义（M8/T1，§3.5 强类型 DTO）。
 *
 * <p>大白话：一张"工具名片"——名字、能干嘛（description）、需要什么格式的入参
 * （inputSchema，一份 OpenAI function-calling 风格的 JSON Schema）。
 * 只描述"能做什么"，<b>不带任何秘钥/地址凭证</b>（§7.11 规则37），可安全返给前端展示。
 *
 * <p>与 {@code com.hify.hify.mcp.dto.ToolDefinition} 同名同构，适配器做映射即可。
 */
public record ToolDefinition(String name, String description, JsonNode inputSchema) {

    /**
     * 前置防御 NPE（§7.3 规则14b）：把可能为 null 的字段兜底成空，避免上层序列化/取值时炸。
     */
    public ToolDefinition {
        if (name == null) {
            name = "";
        }
        if (description == null) {
            description = "";
        }
        if (inputSchema == null) {
            inputSchema = com.fasterxml.jackson.databind.node.NullNode.getInstance();
        }
    }
}
