package com.hify.hify.conversation.service;

import com.hify.hify.common.tool.BuiltinToolRegistry;
import com.hify.hify.common.tool.Tool;
import com.hify.hify.common.tool.ToolCall;
import com.hify.hify.common.tool.ToolDefinition;
import com.hify.hify.conversation.ChatContext;
import com.hify.hify.conversation.tool.ToolLoopRunner;
import com.hify.hify.conversation.tool.ToolStepSink;
import com.hify.hify.mcp.McpService;
import com.hify.hify.mcp.McpToolAdapter;
import com.hify.hify.mcp.dto.McpToolCallResult;
import com.hify.hify.modelprovider.client.ChatMessage;
import com.hify.hify.modelprovider.client.LlmResponse;
import com.hify.hify.modelprovider.client.TokenUsage;
import com.hify.hify.modelprovider.route.ProviderRouter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M8/T3 验收测试：对话编排循环（思考→执行→反思）。
 *
 * <p>大白话：把 LLM 换成 mock 的 {@link ProviderRouter}，直接单测 {@link ToolLoopRunner} 的函数调用循环——
 * ① 问"现在几点了"→ 模型回一张调 {@code current-time} 的小票 → 循环真去执行内置工具 → 真实时间被塞回、
 * 模型据此作答（最终答案含真实当前时间）；② {@code MAX_TOOL_ROUNDS=3} 上限：模型连续要工具时第 4 轮不再调；
 * ③ MCP 工具经 {@link McpToolAdapter} 自动进同一循环（成功拼回 + 失败降级不崩，§4.5）。
 * 不连真网、不连真库、不起 Spring 容器（§7.10 规则35）。
 */
@ExtendWith(MockitoExtension.class)
class ConversationFunctionCallTest {

    @Mock
    private ProviderRouter providerRouter;

    private ToolLoopRunner runner;
    private List<Tool> builtinTools;

    /** 收集循环发出的进度事件（label|status），断言 sendStep 通道被复用。 */
    private final List<String> steps = new ArrayList<>();
    private final ToolStepSink stepSink = (label, status) -> steps.add(label + "|" + status);

    @BeforeEach
    void setUp() {
        runner = new ToolLoopRunner(providerRouter);
        // 真实内置工具（含 current-time），证明"模型自己挑、循环真执行"而非桩
        builtinTools = new BuiltinToolRegistry().allTools();
    }

    private ChatContext ctx() {
        return ChatContext.builder()
                .conversationId("conv-fc")
                .providerRef(10L)
                .question("现在几点了")
                .build();
    }

    private LlmResponse toolCallResp(String toolName, String argsJson) {
        return LlmResponse.builder()
                .finishReason("tool_calls")
                .toolCalls(List.of(new ToolCall("call_1", "function", toolName, argsJson)))
                .promptTokens(5)
                .completionTokens(3)
                .build();
    }

    private LlmResponse finalResp(String content) {
        return LlmResponse.builder()
                .content(content)
                .finishReason("stop")
                .promptTokens(4)
                .completionTokens(6)
                .build();
    }

    /** 从消息列表里取最后一条 tool 角色消息内容（工具真实产出）。 */
    private String lastToolContent(List<ChatMessage> msgs) {
        String v = "";
        for (ChatMessage m : msgs) {
            if ("tool".equals(m.getRole())) {
                v = m.getContent();
            }
        }
        return v;
    }

    /** 验收点7：问"现在几点了"→ 循环真调 current-time → 最终答案含真实当前时间。 */
    @Test
    void testLoop_currentTime_executedAndRealTimeInFinalAnswer() {
        // 第 1 轮无 tool 结果 → 模型要调 current-time；第 2 轮已有 tool 结果 → 模型把真实时间拼进答案
        when(providerRouter.routeWithToolsStream(any(), eq(10L), any(), any())).thenAnswer(inv -> {
            List<ChatMessage> msgs = inv.getArgument(0);
            boolean hasToolResult = msgs.stream().anyMatch(m -> "tool".equals(m.getRole()));
            if (!hasToolResult) {
                return toolCallResp("current-time", "{}");
            }
            return finalResp("现在的时间是 " + lastToolContent(msgs));
        });

        ChatContext ctx = ctx();
        TokenUsage usage = new TokenUsage();
        ToolLoopRunner.LoopResult loop = runner.run(ctx, builtinTools, usage,
                List.of(new ChatMessage("user", "现在几点了")), stepSink, t -> {});

        // 循环恰好两轮（要工具 → 反思作答），routeWithTools 被调 2 次
        verify(providerRouter, times(2)).routeWithToolsStream(any(), eq(10L), any(), any());

        // 最终答案含真实当前年份（current-time 真跑过，产物流入答案）
        int year = OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).getYear();
        assertTrue(loop.finalAnswer().contains(String.valueOf(year)),
                "最终答案应含真实当前时间，实际=" + loop.finalAnswer());

        // 调用留痕：trace 含一条 current-time 成功记录（对话日志铁律：工具调用必须留痕）
        assertEquals(1, ctx.getTrace().size());
        ChatContext.CallTrace t = ctx.getTrace().get(0);
        assertEquals("tool", t.kind());
        assertEquals("current-time", t.toolName());
        assertTrue(Boolean.TRUE.equals(t.success()));

        // SSE 进度通道被复用：一次工具调用 = running + done 两条
        assertTrue(steps.contains("正在调用工具：current-time|running"));
        assertTrue(steps.contains("工具 current-time 返回|done"));

        // token 跨轮累加（两轮 prompt=5+4、completion=3+6）
        assertEquals(9, usage.getPromptTokens());
        assertEquals(9, usage.getCompletionTokens());
    }

    /** 验收点6：MAX_TOOL_ROUNDS=3 上限——模型连续要工具时，第 4 轮不再调（防无限循环）。 */
    @Test
    void testLoop_maxRoundsCap_stopsAtThree() {
        // 模型每轮都要调 current-time（永不给最终答案）
        when(providerRouter.routeWithToolsStream(any(), eq(10L), any(), any()))
                .thenReturn(toolCallResp("current-time", "{}"));

        ChatContext ctx = ctx();
        ToolLoopRunner.LoopResult loop = runner.run(ctx, builtinTools, new TokenUsage(),
                List.of(new ChatMessage("user", "现在几点了")), stepSink, t -> {});

        // 恰好 MAX_TOOL_ROUNDS(=3) 次调用后停止，不再无限循环
        verify(providerRouter, times(ToolLoopRunner.MAX_TOOL_ROUNDS))
                .routeWithToolsStream(any(), eq(10L), any(), any());
        assertEquals(3, ctx.getTrace().size(), "3 轮各执行一次工具，应有 3 条 trace");
        // 触顶后没有最终正文，交由上层用兜底策略作答
        assertEquals("", loop.finalAnswer());
    }

    /** 验收点8：MCP 工具经适配器自动进循环——成功结果拼回、模型据此作答。 */
    @Test
    void testLoop_mcpToolViaAdapter_success() {
        McpService mcpService = org.mockito.Mockito.mock(McpService.class);
        when(mcpService.callTool(eq(1L), eq("order-query"), anyString()))
                .thenReturn(McpToolCallResult.success(1L, "order-query", "订单已发货"));
        Tool mcpTool = new McpToolAdapter(1L,
                new com.hify.hify.mcp.dto.ToolDefinition("order-query", "查订单状态", null), mcpService);
        List<Tool> tools = new ArrayList<>(builtinTools);
        tools.add(mcpTool);

        when(providerRouter.routeWithToolsStream(any(), eq(10L), any(), any())).thenAnswer(inv -> {
            List<ChatMessage> msgs = inv.getArgument(0);
            boolean hasToolResult = msgs.stream().anyMatch(m -> "tool".equals(m.getRole()));
            if (!hasToolResult) {
                return toolCallResp("order-query", "{\"orderId\":\"A1\"}");
            }
            return finalResp("查询结果：" + lastToolContent(msgs));
        });

        ChatContext ctx = ctx();
        ToolLoopRunner.LoopResult loop = runner.run(ctx, tools, new TokenUsage(),
                List.of(new ChatMessage("user", "查订单状态")), stepSink, t -> {});

        // MCP 工具确实经适配器被调用，结果拼回并进入最终答案
        verify(mcpService).callTool(eq(1L), eq("order-query"), anyString());
        assertTrue(loop.finalAnswer().contains("订单已发货"),
                "MCP 工具结果应拼回并进入最终答案，实际=" + loop.finalAnswer());
        ChatContext.CallTrace t = ctx.getTrace().get(0);
        assertEquals("order-query", t.toolName());
        assertTrue(Boolean.TRUE.equals(t.success()));
    }

    /** 验收点8（降级）：MCP 返回失败 → 适配器兜底成 ToolResult.fail → tool 消息塞回、对话不崩（§4.5）。 */
    @Test
    void testLoop_mcpToolViaAdapter_fallbackDoesNotCrash() {
        McpService mcpService = org.mockito.Mockito.mock(McpService.class);
        when(mcpService.callTool(eq(1L), eq("order-query"), anyString()))
                .thenReturn(McpToolCallResult.fallback(1L, "order-query", "工具暂时不可用"));
        Tool mcpTool = new McpToolAdapter(1L,
                new com.hify.hify.mcp.dto.ToolDefinition("order-query", "查订单状态", null), mcpService);
        List<Tool> tools = new ArrayList<>(builtinTools);
        tools.add(mcpTool);

        when(providerRouter.routeWithToolsStream(any(), eq(10L), any(), any())).thenAnswer(inv -> {
            List<ChatMessage> msgs = inv.getArgument(0);
            boolean hasToolResult = msgs.stream().anyMatch(m -> "tool".equals(m.getRole()));
            if (!hasToolResult) {
                return toolCallResp("order-query", "{\"orderId\":\"A1\"}");
            }
            return finalResp("抱歉，暂时查不到订单");
        });

        ChatContext ctx = ctx();
        ToolLoopRunner.LoopResult[] holder = new ToolLoopRunner.LoopResult[1];
        assertDoesNotThrow(() -> holder[0] = runner.run(ctx, tools, new TokenUsage(),
                List.of(new ChatMessage("user", "查订单状态")), stepSink, t -> {}));

        // 降级：trace 记 success=false，但对话链路不中断、仍产出最终答案
        ChatContext.CallTrace t = ctx.getTrace().get(0);
        assertEquals("order-query", t.toolName());
        assertFalse(Boolean.TRUE.equals(t.success()), "MCP 失败应记为 success=false（降级留痕）");
        assertTrue(holder[0].finalAnswer().contains("查不到订单"));
    }

    /**
     * 流式回归验收点：无需调工具时，模型在循环里边生成边经 tokenSink 把字实时吐出（解决 KB 后长时间空白的体验问题），
     * 且最终答案与流式吐字一致。验证方案①之后不再「等整段生成完才切片假流式」。
     */
    @Test
    void testLoop_streamsTokensToSink_whenNoToolCall() {
        List<String> sinkTokens = new ArrayList<>();
        Consumer<String> sink = sinkTokens::add;
        when(providerRouter.routeWithToolsStream(any(), eq(10L), any(), any())).thenAnswer(inv -> {
            Consumer<String> tokenSink = inv.getArgument(3);
            tokenSink.accept("现在");
            tokenSink.accept("的时间是");
            tokenSink.accept("2026");
            return finalResp("现在的时间是 2026");
        });

        ChatContext ctx = ctx();
        ToolLoopRunner.LoopResult loop = runner.run(ctx, builtinTools, new TokenUsage(),
                List.of(new ChatMessage("user", "现在几点了")), stepSink, sink);

        // 流式吐字必须实时经 sink 流出（逐片、顺序一致），而非等循环结束后才一次性出现
        assertEquals(List.of("现在", "的时间是", "2026"), sinkTokens,
                "tokenSink 应实时收到模型逐片输出，实际=" + sinkTokens);
        assertTrue(loop.finalAnswer().contains("2026"), "最终答案应含真实年份，实际=" + loop.finalAnswer());
    }
}
