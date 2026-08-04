package com.hify.hify.mcp;

import com.hify.hify.mcp.dto.McpToolCallResult;
import com.hify.hify.mcp.dto.ToolDefinition;

import java.util.List;

/**
 * MCP 调用接口（M7/T3，§3.2 跨模块只依赖接口）。
 *
 * <p>大白话：这是 mcp 包对外发布的「调用能力」契约，专供 conversation 模块依赖。
 * conversation 只能 {@code @Autowired McpService}，<b>禁止</b> import mcp 包内部的
 * {@code McpClientManager} / {@code McpServer} 等实现类（§3.2 解耦纪律）。
 *
 * <p>契约承诺：{@link #listTools(Long)} 与 {@link #callTool(Long, String, String)} 都不会向上抛
 * MCP 异常（内部已降级，§4.5），调用方只需判返回值即可安全拼回上下文或降级提示，绝不会导致对话链路 500。
 */
public interface McpService {

    /**
     * 发现某 MCP Server 的工具列表。
     *
     * @param serverId MCP Server 配置 id
     * @return 工具定义列表（name/description/inputSchema）；连接/执行失败返回<b>空列表</b>并记审计日志（降级，不抛）
     */
    List<ToolDefinition> listTools(Long serverId);

    /**
     * 调用某 MCP Server 的工具。
     *
     * @param serverId      MCP Server 配置 id
     * @param toolName      工具名（来自 {@link #listTools}）
     * @param argumentsJson 入参 JSON 对象字符串（如 {"msg":"hello"}）
     * @return 调用结果；<b>绝不抛异常</b>（§4.5），失败时 {@code fallback=true} 且 {@code errorMessage} 非空
     */
    McpToolCallResult callTool(Long serverId, String toolName, String argumentsJson);
}
