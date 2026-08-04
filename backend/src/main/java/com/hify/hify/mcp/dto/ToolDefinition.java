package com.hify.hify.mcp.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 工具定义（M7/T2，§7.11 安全规约）。
 *
 * <p>大白话：MCP Server 告诉你「我会干这些活儿」，每个活儿就是一张 ToolDefinition 名片——
 * 名字、能干嘛（description）、需要什么格式的入参（inputSchema，一份 JSON Schema）。
 * 它只描述「能做什么」，<b>不带任何秘钥/地址凭证</b>（§7.11 规则37），可以安全返给前端做展示。
 */
public record ToolDefinition(String name, String description, JsonNode inputSchema) {

    /**
     * 紧凑规范构造：把可能为 null 的字段兜底成空，避免上层 NPE（§7.3 规则14b 前置防御）。
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
