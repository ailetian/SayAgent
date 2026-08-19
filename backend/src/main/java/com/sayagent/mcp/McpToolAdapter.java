package com.sayagent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sayagent.common.tool.Tool;
import com.sayagent.common.tool.ToolDefinition;
import com.sayagent.common.tool.ToolResult;
import com.sayagent.mcp.dto.McpToolCallResult;

import java.util.Map;

/**
 * MCP 工具适配器（M8/T3）：把「某个 MCP Server 暴露的某个工具」适配成统一 {@link Tool} 接口。
 *
 * <p>大白话：模型只会说"我要调 current-time 这种名字的工具"。MCP 那边每个 server 有自己的一套工具，
 * 这里把"serverId 对应的某个工具"包成一个统一 Tool——编排循环只认 {@link Tool} 接口，
 * 内部通过 {@link McpService}（mcp 对外接口，§3.2 跨模块只依赖接口）去调真工具，
 * <b>绝不直接 import McpClientManager / McpServer 等 mcp 内部类</b>。
 *
 * <p>调用契约：{@link McpService#callTool} 永不向上抛（M7 契约 + §4.5），这里再兜底一层——
 * 即便 McpService 破约抛异常，也降级成 {@link ToolResult#fail}，<b>绝不中断对话链路</b>。
 */
public class McpToolAdapter implements Tool {

    private final Long serverId;
    private final com.sayagent.mcp.dto.ToolDefinition mcpDef;
    private final McpService mcpService;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public McpToolAdapter(Long serverId, com.sayagent.mcp.dto.ToolDefinition mcpDef, McpService mcpService) {
        this.serverId = serverId;
        this.mcpDef = mcpDef;
        this.mcpService = mcpService;
    }

    @Override
    public ToolDefinition getDefinition() {
        // mcp.dto.ToolDefinition(name, description, inputSchema, riskLevel, dataSensitivity) 与
        // common.tool.ToolDefinition 同名同构，直接映射并携带 riskLevel + dataSensitivity
        // （M10/T3+T4：canonical 的最终风险级别 / 数据敏感度赋值点）
        return new ToolDefinition(mcpDef.name(), mcpDef.description(), mcpDef.inputSchema(), mcpDef.riskLevel(), mcpDef.dataSensitivity());
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String argsJson = toJson(args);
        try {
            McpToolCallResult res = mcpService.callTool(serverId, mcpDef.name(), argsJson);
            if (res.success()) {
                return ToolResult.ok(res.result());
            }
            // 降级结果：把 errorMessage 透出，让模型自己决定怎么作答（§4.5 不中断）
            return ToolResult.fail(res.errorMessage());
        } catch (Exception e) {
            // 防御：McpService 破约直接抛异常也兜底降级，绝不向上抛（§4.5）
            return ToolResult.fail("MCP 工具调用异常：" + e.getMessage());
        }
    }

    /** 把模型回传的参数表序列化回 JSON 串（兜底 {}，§7.3 前置防御）。 */
    private String toJson(Map<String, Object> args) {
        try {
            return MAPPER.writeValueAsString(args == null ? Map.of() : args);
        } catch (Exception e) {
            return "{}";
        }
    }
}
