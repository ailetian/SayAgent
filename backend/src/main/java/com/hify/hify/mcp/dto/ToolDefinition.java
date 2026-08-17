package com.hify.hify.mcp.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.hify.hify.common.tool.DataSensitivity;
import com.hify.hify.common.tool.RiskLevel;

/**
 * 工具定义（M7/T2，§7.11 安全规约）。
 *
 * <p>大白话：MCP Server 告诉你「我会干这些活儿」，每个活儿就是一张 ToolDefinition 名片——
 * 名字、能干嘛（description）、需要什么格式的入参（inputSchema，一份 JSON Schema）。
 * 它只描述「能做什么」，<b>不带任何秘钥/地址凭证</b>（§7.11 规则37），可以安全返给前端做展示。
 *
 * <p>新增 {@code riskLevel}（M10/T3）：由 {@code McpClientManager.listTools()} 解析协议
 * {@code annotations} 写入，再经 {@code McpToolAdapter} 透传到 canonical {@code common.tool.ToolDefinition}。
 * 新增 {@code dataSensitivity}（M10/T4）：由 {@code McpClientManager.listTools()} 按所属
 * {@code McpServer.getDataSensitivity()} 标注（MCP 协议无标准敏感度字段，靠管理员注册时人工标注），
 * 同样经 {@code McpToolAdapter} 透传到 canonical。本类仅为中间载体——<b>不得越级</b>给 canonical 直接赋值。
 */
public record ToolDefinition(String name, String description, JsonNode inputSchema, RiskLevel riskLevel, DataSensitivity dataSensitivity) {

    /**
     * 紧凑规范构造：兜底 null + riskLevel 默认 L1（宁严）+ dataSensitivity 默认 INTERNAL（§7.3 规则14b 前置防御 + M10/T3/T4）。
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
        if (riskLevel == null) {
            riskLevel = RiskLevel.L1_WRITE_REVERSIBLE;
        }
        if (dataSensitivity == null) {
            dataSensitivity = DataSensitivity.INTERNAL;
        }
    }

    /**
     * 向后兼容构造（M10/T4）：未显式传 dataSensitivity 时默认 INTERNAL，避免破坏既有 mcp.dto 构造点。
     */
    public ToolDefinition(String name, String description, JsonNode inputSchema, RiskLevel riskLevel) {
        this(name, description, inputSchema, riskLevel, DataSensitivity.INTERNAL);
    }

    /**
     * 向后兼容构造（M10/T3）：未显式传 riskLevel/dataSensitivity 时默认 L1/INTERNAL，避免破坏既有 3 参调用点。
     */
    public ToolDefinition(String name, String description, JsonNode inputSchema) {
        this(name, description, inputSchema, RiskLevel.L1_WRITE_REVERSIBLE, DataSensitivity.INTERNAL);
    }
}
