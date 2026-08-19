package com.sayagent.common.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 工具定义（M8/T1，§3.5 强类型 DTO）。
 *
 * <p>大白话：一张"工具名片"——名字、能干嘛（description）、需要什么格式的入参
 * （inputSchema，一份 OpenAI function-calling 风格的 JSON Schema）。
 * 只描述"能做什么"，<b>不带任何秘钥/地址凭证</b>（§7.11 规则37），可安全返给前端展示。
 *
 * <p>与 {@code com.sayagent.mcp.dto.ToolDefinition} 同名同构，适配器做映射即可。
 *
 * <p>新增 {@code riskLevel}（M10/T3）：工具危险度标签，默认 {@code L1}（宁严），供 T5 执行闸与 T6 前端知情。
 * 新增 {@code dataSensitivity}（M10/T4）：数据敏感度标签，默认 {@code INTERNAL}，与 riskLevel 正交，供 T6 授权知情。
 */
public record ToolDefinition(String name, String description, JsonNode inputSchema, RiskLevel riskLevel, DataSensitivity dataSensitivity) {

    /**
     * 前置防御 NPE（§7.3 规则14b）+ 默认值（M10/T3 riskLevel 默认 L1 宁严；M10/T4 dataSensitivity 默认 INTERNAL）。
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
     * 向后兼容构造（M10/T4）：未显式传 dataSensitivity 时默认 INTERNAL，避免破坏既有 canonical 构造点
     * （CurrentTimeTool、McpToolAdapter 经本构造器委托）。
     */
    public ToolDefinition(String name, String description, JsonNode inputSchema, RiskLevel riskLevel) {
        this(name, description, inputSchema, riskLevel, DataSensitivity.INTERNAL);
    }

    /**
     * 向后兼容构造（M10/T3）：未显式传 riskLevel/dataSensitivity 时默认 L1/INTERNAL，避免破坏既有 2 处
     * 3 参调用点（测试与内置工具兼容构造）。
     */
    public ToolDefinition(String name, String description, JsonNode inputSchema) {
        this(name, description, inputSchema, RiskLevel.L1_WRITE_REVERSIBLE, DataSensitivity.INTERNAL);
    }
}
