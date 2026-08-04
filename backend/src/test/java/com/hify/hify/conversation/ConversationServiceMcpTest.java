package com.hify.hify.conversation;

import com.hify.hify.agent.dto.AgentVO;
import com.hify.hify.agent.service.AgentService;
import com.hify.hify.common.exception.BizException;
import com.hify.hify.common.exception.ErrorCode;
import com.hify.hify.conversation.dto.ChatRequest;
import com.hify.hify.conversation.entity.Conversation;
import com.hify.hify.conversation.repository.ConversationRepository;
import com.hify.hify.conversation.repository.MessageRepository;
import com.hify.hify.conversation.service.ConversationService;
import com.hify.hify.conversation.ConversationLogAsyncWriter;
import com.hify.hify.knowledge.retriever.RetrievalPort;
import com.hify.hify.mcp.McpService;
import com.hify.hify.mcp.dto.McpToolCallResult;
import com.hify.hify.mcp.dto.ToolDefinition;
import com.hify.hify.modelprovider.client.ChatMessage;
import com.hify.hify.modelprovider.client.TokenUsage;
import com.hify.hify.modelprovider.domain.enums.ProviderType;
import com.hify.hify.modelprovider.dto.ModelProviderVO;
import com.hify.hify.modelprovider.service.LlmStreamService;
import com.hify.hify.modelprovider.service.ModelService;
import com.hify.hify.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M7/T3 验收测试：ConversationService 接入 MCP（拼回 + 降级）。
 *
 * <p>大白话：用 Mockito 把所有外部依赖（含 McpService）都 mock 掉，走一条「假对话」，
 * 验证两件事——① Agent 配了 toolRefs 时，MCP 工具结果被拼回发给 LLM 的上下文（§2 模块8）；
 * ② MCP 失败（返回降级结果 / 甚至破约抛异常）时对话不崩、上下文带「工具暂时不可用」提示（§4.5）。
 * 不连真网、不连真库（§7.10 规则35）。
 */
@ExtendWith(MockitoExtension.class)
class ConversationServiceMcpTest {

    @Mock ConversationRepository conversationRepository;
    @Mock MessageRepository messageRepository;
    @Mock UserService userService;
    @Mock ExecutorService sseExecutor;
    @Mock AgentService agentService;
    @Mock ModelService modelService;
    @Mock LlmStreamService llmStreamService;
    @Mock RetrievalPort retrievalPort;
    @Mock ConversationLogAsyncWriter conversationLogAsyncWriter;
    @Mock McpService mcpService;

    @InjectMocks ConversationService conversationService;

    @BeforeEach
    void setUp() {
        // 已登录用户（admin），供 currentUser() 使用
        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.getName()).thenReturn("admin");
        SecurityContext sc = mock(SecurityContext.class);
        when(sc.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(sc);
        when(userService.resolveUserId("admin")).thenReturn(1L);
    }

    /** 摆好一条能跑完的「假对话」公共桩（sseExecutor 同步、建会话、Agent 配 toolRefs=[1L]、LLM 立即完成）。 */
    private void stubCommon() {
        // sseExecutor 同步执行，使 orchestrate 同步跑完（脱离 @Transactional，MCP 在事务外，§4.2）
        when(sseExecutor.submit(any(Runnable.class))).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return CompletableFuture.completedFuture(null);
        });
        when(conversationRepository.save(any(Conversation.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.countByConversationId(anyString())).thenReturn(0L);
        when(messageRepository.findByConversationIdOrderBySeqAsc(anyString())).thenReturn(List.of());
        when(messageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Agent：modelProviderId=10L，toolRefs=[1L]
        AgentVO agent = new AgentVO(1L, "agent", "desc", "系统提示", 10L, "gpt-4",
                true, true, 0, null, null, null, null, List.of(), List.of(1L), null, null);
        when(agentService.getAgent(1L)).thenReturn(agent);

        ModelProviderVO provider = mock(ModelProviderVO.class);
        when(provider.providerType()).thenReturn(ProviderType.OPENAI);
        when(provider.model()).thenReturn("gpt-4");
        when(modelService.getProvider(10L)).thenReturn(provider);

        // LLM 流式：捕获发给它的 messages 参数后立刻完成（逐 token 同步）
        when(llmStreamService.stream(anyList(), anyLong(), any(TokenUsage.class)))
                .thenReturn(Flux.just("ok"));
    }

    /** 验收点3：Agent 配 toolRefs → 调 MCP 拿到结果 → 拼回 LLM 上下文（含工具结果文本）。 */
    @Test
    void testStream_有工具引用_结果拼回上下文() {
        stubCommon();
        when(mcpService.listTools(1L)).thenReturn(List.of(new ToolDefinition("echo", "echo tool", null)));
        when(mcpService.callTool(eq(1L), eq("echo"), anyString()))
                .thenReturn(McpToolCallResult.success(1L, "echo", "echo:hello"));

        conversationService.stream(new ChatRequest(null, "查订单状态", "1"));

        ArgumentCaptor<List<ChatMessage>> cap = ArgumentCaptor.forClass(List.class);
        verify(llmStreamService).stream(cap.capture(), anyLong(), any(TokenUsage.class));
        String joined = cap.getValue().stream()
                .map(ChatMessage::getContent).reduce("", (a, b) -> a + "\n" + b);
        assertTrue(joined.contains("echo:hello"), "工具结果应被拼回 LLM 上下文: " + joined);
        assertTrue(joined.contains("工具结果"), "应出现工具结果标记: " + joined);
    }

    /** 验收点4：MCP 返回降级结果 → 对话不崩、上下文含「工具暂时不可用」提示、无 500。 */
    @Test
    void testStream_MCP失败_降级不中断对话() {
        stubCommon();
        when(mcpService.listTools(1L)).thenReturn(List.of(new ToolDefinition("echo", "echo tool", null)));
        when(mcpService.callTool(eq(1L), eq("echo"), anyString()))
                .thenReturn(McpToolCallResult.fallback(1L, "echo", "工具暂时不可用"));

        assertDoesNotThrow(() -> conversationService.stream(new ChatRequest(null, "查订单状态", "1")));

        ArgumentCaptor<List<ChatMessage>> cap = ArgumentCaptor.forClass(List.class);
        verify(llmStreamService).stream(cap.capture(), anyLong(), any(TokenUsage.class));
        String joined = cap.getValue().stream()
                .map(ChatMessage::getContent).reduce("", (a, b) -> a + "\n" + b);
        assertTrue(joined.contains("工具暂时不可用"), "MCP 失败应出现降级提示: " + joined);
    }

    /** 验收点4（防御）：即便 McpService 破约直接抛异常，conversation 也兜底降级、不崩（§4.5）。 */
    @Test
    void testStream_MCP服务破约抛异常_仍降级不崩() {
        stubCommon();
        when(mcpService.listTools(1L)).thenThrow(new BizException(ErrorCode.MCP_CALL_FAILED, "boom"));

        assertDoesNotThrow(() -> conversationService.stream(new ChatRequest(null, "查订单状态", "1")));

        ArgumentCaptor<List<ChatMessage>> cap = ArgumentCaptor.forClass(List.class);
        verify(llmStreamService).stream(cap.capture(), anyLong(), any(TokenUsage.class));
        String joined = cap.getValue().stream()
                .map(ChatMessage::getContent).reduce("", (a, b) -> a + "\n" + b);
        assertTrue(joined.contains("工具暂时不可用"), "McpService 破约抛异常也应降级: " + joined);
    }
}
