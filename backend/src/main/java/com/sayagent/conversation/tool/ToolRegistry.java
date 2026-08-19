package com.sayagent.conversation.tool;

import com.sayagent.common.tool.BuiltinToolRegistry;
import com.sayagent.common.tool.Tool;
import com.sayagent.mcp.McpService;
import com.sayagent.mcp.McpToolAdapter;
import com.sayagent.mcp.dto.ToolDefinition;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 工具注册表（M8/T3）：把 Agent 配置的 toolRefs（MCP Server id）解析成「本轮可用工具」列表。
 *
 * <p>大白话：Agent 的 {@code toolRefs} 存的是「MCP Server 的 id 列表」。这里逐 server 发现工具包成
 * {@link McpToolAdapter}，再并上平台内置工具（如 current-time，来自 {@link BuiltinToolRegistry}）。
 * 编排循环只认 {@code Tool} 接口，模型自己从这张清单里挑要调哪个。
 *
 * <p>技能（skill）是「提示词型」，已通过 system prompt 拼装（见 {@code SkillService.composePersona}），
 * <b>不</b>在此处作为工具出现——与 MCP（执行动作）/内置工具正交，避免重名与职责混淆。
 *
 * <p>去重：按工具定义名去重（§3.2 / §4.5）。
 *
 * <p>解耦纪律（§3.2）：只 {@code @Autowired McpService}（对外接口）与 {@link BuiltinToolRegistry}，
 * <b>禁止</b> import McpClientManager / McpServer 等内部类。
 */
@Component
public class ToolRegistry {

    private final McpService mcpService;
    private final BuiltinToolRegistry builtinToolRegistry;

    public ToolRegistry(McpService mcpService) {
        this.mcpService = mcpService;
        // 内置工具注册表自持有单例：current-time 等平台自带技能始终可用（模型可随时调用）
        this.builtinToolRegistry = new BuiltinToolRegistry();
    }

    /**
     * 由 Agent 的 toolRefs（MCP server id）解析出本轮可用工具。
     * 按工具定义名去重（避免 MCP 暴露的同名工具重复）。
     *
     * @param toolRefs  Agent 配置的 MCP server id 列表（可能为 null / 空）
     * @return 工具列表（含 MCP 适配器 + 内置工具）；不会返回 null
     */
    public List<Tool> resolve(List<Long> toolRefs) {
        List<Tool> tools = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        // 1) MCP 工具
        if (toolRefs != null) {
            for (Long serverId : toolRefs) {
                List<ToolDefinition> defs = mcpService.listTools(serverId);
                if (defs != null) {
                    for (ToolDefinition td : defs) {
                        if (names.add(td.name())) {
                            tools.add(new McpToolAdapter(serverId, td, mcpService));
                        }
                    }
                }
            }
        }
        // 2) 内置工具（current-time 等）始终可用，模型可随时调用
        for (Tool t : builtinToolRegistry.allTools()) {
            if (names.add(t.getDefinition().name())) {
                tools.add(t);
            }
        }
        return tools;
    }
}
