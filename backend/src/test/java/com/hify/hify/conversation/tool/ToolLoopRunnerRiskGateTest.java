package com.hify.hify.conversation.tool;

import com.hify.hify.common.tool.DataSensitivity;
import com.hify.hify.common.tool.RiskLevel;
import com.hify.hify.common.tool.Tool;
import com.hify.hify.common.tool.ToolCall;
import com.hify.hify.common.tool.ToolDefinition;
import com.hify.hify.common.tool.ToolResult;
import com.hify.hify.conversation.ChatContext;
import com.hify.hify.modelprovider.client.ChatMessage;
import com.hify.hify.modelprovider.client.LlmResponse;
import com.hify.hify.modelprovider.client.TokenUsage;
import com.hify.hify.modelprovider.route.ProviderRouter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * M10/T5 验收测试：执行闸分级判定（L2/L3 一期默认拒绝）。
 *
 * <p>大白话：把 LLM 换成 mock 的 {@link ProviderRouter}，直接单测 {@link ToolLoopRunner}
 * 在调工具前的「风险闸」——L0/L1（只读/可逆）照常执行；L2/L3（不可逆/高危）一期不进 execute、
 * 把"需管理员确认，当前对话模式不支持自动执行"的提示塞回对话上下文、循环继续不中断。
 * 不连真网、不连真库、不起 Spring 容器（§7.10 规则35）。
 */
@ExtendWith(MockitoExtension.class)
class ToolLoopRunnerRiskGateTest {

    @Mock
    private ProviderRouter providerRouter;

    private ToolLoopRunner runner;

    /** 收集循环发出的进度事件（label|status），断言 sendStep 通道被复用。 */
    private final java.util.List<String> steps = new java.util.ArrayList<>();
    private final ToolStepSink stepSink = (label, status) -> steps.add(label + "|" + status);

    @BeforeEach
    void setUp() {
        runner = new ToolLoopRunner(providerRouter);
    }

    /** 可控假工具：记录 execute() 是否被实际调用，危险度由构造指定。 */
    static final class FakeTool implements Tool {
        final String name;
        final RiskLevel risk;
        boolean executed = false;

        FakeTool(String name, RiskLevel risk) {
            this.name = name;
            this.risk = risk;
        }

        @Override
        public ToolDefinition getDefinition() {
            return new ToolDefinition(name, "测试工具 " + name, null, risk, DataSensitivity.INTERNAL);
        }

        @Override
        public ToolResult execute(Map<String, Object> args) {
            executed = true;
            return ToolResult.ok("done:" + name);
        }
    }

    private ChatContext ctx() {
        return ChatContext.builder()
                .conversationId("conv-t5")
                .providerRef(10L)
                .question("测试执行闸")
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

    /** 从消息列表里取最后一条 tool 角色消息内容（工具真实产出 / 闸拦截提示）。 */
    private String lastToolContent(List<ChatMessage> msgs) {
        String v = "";
        for (ChatMessage m : msgs) {
            if ("tool".equals(m.getRole())) {
                v = m.getContent();
            }
        }
        return v;
    }

    /** 验收点3 + 5：L2 工具请求 → 不进 execute、提示塞回、对话不中断正常收尾。 */
    @Test
    void l2Tool_blockedAndNotExecuted() {
        FakeTool l2 = new FakeTool("cancel-order", RiskLevel.L2_IRREVERSIBLE);
        List<Tool> tools = List.of(l2);
        when(providerRouter.routeWithToolsStream(any(), any(), any(), any())).thenAnswer(inv -> {
            List<ChatMessage> msgs = inv.getArgument(0);
            boolean hasToolResult = msgs.stream().anyMatch(m -> "tool".equals(m.getRole()));
            if (!hasToolResult) {
                return toolCallResp("cancel-order", "{}");
            }
            return finalResp("好的，我会转告：该操作暂不支持自动执行");
        });

        ChatContext ctx = ctx();
        ToolLoopRunner.LoopResult loop = runner.run(ctx, tools, new TokenUsage(),
                List.of(new ChatMessage("user", "取消订单A1")), stepSink, t -> {});

        // L2 工具绝不应被实际执行（执行闸拦截，不进 execute）
        assertFalse(l2.executed, "L2 工具绝不应被实际执行（执行闸拦截）");

        // 提示塞回：tool 角色消息含拦截提示
        String toolMsg = lastToolContent(loop.messages());
        assertTrue(toolMsg.contains("需管理员确认"),
                "拦截提示应含'需管理员确认'，实际=" + toolMsg);
        assertTrue(toolMsg.contains("不支持自动执行"),
                "拦截提示应含'不支持自动执行'，实际=" + toolMsg);

        // 对话不中断：仍产出最终答案（§4.5 降级纪律，不抛 500）
        assertTrue(loop.finalAnswer().contains("暂不支持"),
                "对话应正常收尾而非中断，实际=" + loop.finalAnswer());

        // 调用轨迹留痕：L2 工具记 blocked 状态（满足对话日志铁律）
        assertEquals(1, ctx.getTrace().size(), "应有一条 L2 工具调用轨迹");
        ChatContext.CallTrace t = ctx.getTrace().get(0);
        assertEquals("blocked", t.status(), "拦截应记 blocked 状态");
        assertEquals("cancel-order", t.toolName());
    }

    /** 验收点4（回归）：L0 工具正常执行、结果拼回、零回归。 */
    @Test
    void l0Tool_executesNormally() {
        FakeTool l0 = new FakeTool("check-stock", RiskLevel.L0_READONLY_SAFE);
        List<Tool> tools = List.of(l0);
        when(providerRouter.routeWithToolsStream(any(), any(), any(), any())).thenAnswer(inv -> {
            List<ChatMessage> msgs = inv.getArgument(0);
            boolean hasToolResult = msgs.stream().anyMatch(m -> "tool".equals(m.getRole()));
            if (!hasToolResult) {
                return toolCallResp("check-stock", "{}");
            }
            return finalResp("库存结果：" + lastToolContent(msgs));
        });

        ChatContext ctx = ctx();
        ToolLoopRunner.LoopResult loop = runner.run(ctx, tools, new TokenUsage(),
                List.of(new ChatMessage("user", "查库存")), stepSink, t -> {});

        // L0 工具应正常执行
        assertTrue(l0.executed, "L0 工具应正常执行");
        // 结果拼回最终答案
        assertTrue(loop.finalAnswer().contains("库存结果：done:check-stock"),
                "L0 结果应拼回，实际=" + loop.finalAnswer());
    }

    /** 验收点3 + 5：L3 高危工具同样拦截、绝不执行、不抛异常（循环继续）。 */
    @Test
    void l3Tool_blocked_noException() {
        FakeTool l3 = new FakeTool("transfer-money", RiskLevel.L3_HIGH_RISK);
        List<Tool> tools = List.of(l3);
        when(providerRouter.routeWithToolsStream(any(), any(), any(), any())).thenAnswer(inv -> {
            List<ChatMessage> msgs = inv.getArgument(0);
            boolean hasToolResult = msgs.stream().anyMatch(m -> "tool".equals(m.getRole()));
            if (!hasToolResult) {
                return toolCallResp("transfer-money", "{}");
            }
            return finalResp("抱歉，资金操作需管理员确认");
        });

        ChatContext ctx = ctx();
        assertDoesNotThrow(() -> runner.run(ctx, tools, new TokenUsage(),
                List.of(new ChatMessage("user", "转钱")), stepSink, t -> {}),
                "L3 拦截不应抛异常中断对话");
        assertFalse(l3.executed, "L3 工具绝不应被执行");
        // 进度通道出现拦截事件
        assertTrue(steps.stream().anyMatch(s -> s.contains("被风险闸拦截")),
                "SSE 进度应出现风险闸拦截事件，实际=" + steps);
    }

    /** 验收点4（回归）：L1 写但可逆工具正常执行（默认级别不误拦）。 */
    @Test
    void l1Tool_executesNormally() {
        FakeTool l1 = new FakeTool("draft-email", RiskLevel.L1_WRITE_REVERSIBLE);
        List<Tool> tools = List.of(l1);
        when(providerRouter.routeWithToolsStream(any(), any(), any(), any())).thenAnswer(inv -> {
            List<ChatMessage> msgs = inv.getArgument(0);
            boolean hasToolResult = msgs.stream().anyMatch(m -> "tool".equals(m.getRole()));
            if (!hasToolResult) {
                return toolCallResp("draft-email", "{}");
            }
            return finalResp("已生成草稿：" + lastToolContent(msgs));
        });

        ChatContext ctx = ctx();
        ToolLoopRunner.LoopResult loop = runner.run(ctx, tools, new TokenUsage(),
                List.of(new ChatMessage("user", "写草稿")), stepSink, t -> {});

        assertTrue(l1.executed, "L1 工具应正常执行（一期仅拦 L2/L3）");
        assertTrue(loop.finalAnswer().contains("已生成草稿：done:draft-email"),
                "L1 结果应拼回，实际=" + loop.finalAnswer());
    }
}
