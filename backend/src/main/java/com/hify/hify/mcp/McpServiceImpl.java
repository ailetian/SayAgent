package com.hify.hify.mcp;

import com.hify.hify.common.exception.BizException;
import com.hify.hify.common.exception.ErrorCode;
import com.hify.hify.mcp.dto.McpToolCallResult;
import com.hify.hify.mcp.dto.ToolDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP 调用服务实现（M7/T3，§3.2：mcp 包内可依赖 McpClientManager / McpServerRepository）。
 *
 * <p>大白话：conversation 通过 {@link McpService} 接口找我们办事。我们拿 serverId 去通讯录
 * （{@code McpServerRepository}）翻出连接配置（{@code McpServer} 实体），交给 T2 的
 * {@link McpClientManager} 去连/发现/调用。任何一步失败都降级成 {@link McpToolCallResult#fallback}，
 * 绝不把异常甩给对话链路（§4.5），并记审计 INFO 日志（§7.11 规则38）。
 */
@Slf4j
@Service
public class McpServiceImpl implements McpService {

    private final McpClientManager mcpClientManager;
    private final McpServerRepository mcpServerRepository;

    @Autowired
    public McpServiceImpl(McpClientManager mcpClientManager, McpServerRepository mcpServerRepository) {
        this.mcpClientManager = mcpClientManager;
        this.mcpServerRepository = mcpServerRepository;
    }

    @Override
    public List<ToolDefinition> listTools(Long serverId) {
        try {
            McpServer server = resolveServer(serverId);
            List<ToolDefinition> tools = mcpClientManager.listTools(server);
            log.info("mcp.discover audit serverId={} ok=true count={}", serverId, tools.size());
            return tools;
        } catch (BizException e) {
            // §4.5 降级：不向上抛，返回空列表；记审计 INFO（含 ok=false/fallback=true）
            log.info("mcp.discover audit serverId={} ok=false fallback=true errorCode={}", serverId, e.getErrorCode());
            log.warn("mcp.discover fail serverId={} msg={}", serverId, e.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public McpToolCallResult callTool(Long serverId, String toolName, String argumentsJson) {
        try {
            McpServer server = resolveServer(serverId);
            String result = mcpClientManager.callTool(server, toolName, argumentsJson);
            log.info("mcp.call audit serverId={} toolName={} ok=true", serverId, toolName);
            return McpToolCallResult.success(serverId, toolName, result);
        } catch (BizException e) {
            // §4.5 降级：转 fallback，绝不向上抛；记审计 INFO（含 ok=false/fallback=true）
            log.info("mcp.call audit serverId={} toolName={} ok=false fallback=true errorCode={}",
                    serverId, toolName, e.getErrorCode());
            log.warn("mcp.call fail serverId={} toolName={} msg={}", serverId, toolName, e.getMessage());
            return McpToolCallResult.fallback(serverId, toolName, "工具暂时不可用");
        }
    }

    /** 解析 serverId → McpServer 实体；不存在/软删则抛 MCP_CALL_FAILED（由上层降级）。 */
    private McpServer resolveServer(Long serverId) {
        if (serverId == null) {
            throw new BizException(ErrorCode.MCP_CALL_FAILED, "MCP serverId 为空");
        }
        return mcpServerRepository.findById(serverId)
                .orElseThrow(() -> new BizException(ErrorCode.MCP_CALL_FAILED,
                        "MCP Server 不存在或已停用: " + serverId));
    }
}
