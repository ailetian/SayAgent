package com.sayagent.mcp;

import com.sayagent.mcp.dto.McpToolCallResult;
import com.sayagent.mcp.dto.ToolDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MCP 加载链路的「启用状态」校验单测（§7.10 规则34 命名 test方法_场景_预期）。
 *
 * <p>大白话：管理员在通讯录里把某个内部系统「停用」后，对话时就不该再去连它、
 * 也不该把它的工具端到模型面前。本测试盯死这条：
 * <ul>
 *   <li>启用(status=1)：正常发现工具、正常调用；</li>
 *   <li>停用(status=0)：发现返回空列表、调用返回 fallback，且<b>根本不去连</b>（不碰 McpClientManager）；</li>
 *   <li>status=null 脏数据：按 fail-closed 当作停用；</li>
 *   <li>serverId 为空：同样降级，不抛异常污染对话链路（§4.5）。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class McpServiceStatusTest {

    private static final Long SERVER_ID = 10L;
    private static final String TOOL_NAME = "add";
    private static final String ARGS_JSON = "{\"a\":1,\"b\":2}";

    @Mock
    private McpClientManager mcpClientManager;

    @Mock
    private McpServerRepository mcpServerRepository;

    @InjectMocks
    private McpServiceImpl mcpService;

    /** 造一个指定启用状态的 Server 配置。 */
    private McpServer buildServer(Integer status) {
        McpServer s = new McpServer();
        s.setName("demo-stdio");
        s.setAddress("python demo.py");
        s.setType("STDIO");
        s.setStatus(status);
        return s;
    }

    @Test
    @DisplayName("listTools：启用(status=1) 正常发现工具")
    void testListTools_enabled_returnsTools() {
        when(mcpServerRepository.findById(SERVER_ID))
                .thenReturn(Optional.of(buildServer(McpServerStatus.ENABLED)));
        when(mcpClientManager.listTools(any()))
                .thenReturn(List.of(new ToolDefinition(TOOL_NAME, "两数相加", null)));

        List<ToolDefinition> tools = mcpService.listTools(SERVER_ID);

        assertEquals(1, tools.size());
        assertEquals(TOOL_NAME, tools.get(0).name());
    }

    @Test
    @DisplayName("listTools：停用(status=0) 返回空列表且不连接 Server")
    void testListTools_disabled_returnsEmptyAndNoConnect() {
        when(mcpServerRepository.findById(SERVER_ID))
                .thenReturn(Optional.of(buildServer(McpServerStatus.DISABLED)));

        List<ToolDefinition> tools = mcpService.listTools(SERVER_ID);

        assertTrue(tools.isEmpty(), "停用的 Server 不得暴露任何工具");
        verify(mcpClientManager, never()).listTools(any());
    }

    @Test
    @DisplayName("listTools：status 为 null 的脏数据按停用处理")
    void testListTools_nullStatus_treatedAsDisabled() {
        when(mcpServerRepository.findById(SERVER_ID))
                .thenReturn(Optional.of(buildServer(null)));

        assertTrue(mcpService.listTools(SERVER_ID).isEmpty());
        verify(mcpClientManager, never()).listTools(any());
    }

    @Test
    @DisplayName("listTools：Server 不存在或已软删返回空列表")
    void testListTools_notFound_returnsEmpty() {
        when(mcpServerRepository.findById(SERVER_ID)).thenReturn(Optional.empty());

        assertTrue(mcpService.listTools(SERVER_ID).isEmpty());
        verify(mcpClientManager, never()).listTools(any());
    }

    @Test
    @DisplayName("callTool：停用(status=0) 返回 fallback 且不执行调用")
    void testCallTool_disabled_returnsFallback() {
        when(mcpServerRepository.findById(SERVER_ID))
                .thenReturn(Optional.of(buildServer(McpServerStatus.DISABLED)));

        McpToolCallResult result = mcpService.callTool(SERVER_ID, TOOL_NAME, ARGS_JSON);

        assertFalse(result.success());
        assertTrue(result.fallback());
        assertEquals(TOOL_NAME, result.toolName());
        verify(mcpClientManager, never()).callTool(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("callTool：启用(status=1) 正常执行并返回成功")
    void testCallTool_enabled_returnsSuccess() {
        when(mcpServerRepository.findById(SERVER_ID))
                .thenReturn(Optional.of(buildServer(McpServerStatus.ENABLED)));
        when(mcpClientManager.callTool(any(), anyString(), anyString())).thenReturn("3");

        McpToolCallResult result = mcpService.callTool(SERVER_ID, TOOL_NAME, ARGS_JSON);

        assertTrue(result.success());
        assertFalse(result.fallback());
        assertEquals("3", result.result());
    }

    @Test
    @DisplayName("listTools：serverId 为空降级为空列表，不查库")
    void testListTools_nullServerId_returnsEmpty() {
        assertTrue(mcpService.listTools(null).isEmpty());
        verify(mcpServerRepository, never()).findById(any());
    }
}
