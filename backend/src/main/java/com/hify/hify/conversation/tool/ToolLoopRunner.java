package com.hify.hify.conversation.tool;

import com.hify.hify.common.tool.RiskLevel;
import com.hify.hify.common.tool.Tool;
import com.hify.hify.common.tool.ToolCall;
import com.hify.hify.common.tool.ToolDefinition;
import com.hify.hify.common.tool.ToolResult;
import com.hify.hify.conversation.ChatContext;
import com.hify.hify.conversation.ChatContext.CallTrace;
import com.hify.hify.modelprovider.client.ChatMessage;
import com.hify.hify.modelprovider.client.LlmResponse;
import com.hify.hify.modelprovider.client.TokenUsage;
import com.hify.hify.modelprovider.route.ProviderRouter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 对话编排循环：思考 → 执行 → 反思（M8/T3）。
 *
 * <p>大白话：把"对话前偷偷调第一个 MCP 工具"的临时写法（M7/T3 伪调用 hack）升级成真正的函数调用通道。
 * 每轮把消息 + 工具清单发给模型；模型若回 {@code tool_calls}，我们就真去执行那个工具、把结果塞回去、再问模型；
 * 模型若不再要工具（finish_reason≠tool_calls），就跳出用这轮正文作答。MCP 工具和内置 current-time 都进这个循环，
 * 模型自己挑调哪个。前端进度（sendStep）与调用留痕（CallTrace 落 trace_json）按 M6 既有通道复用。
 */
@Component
public class ToolLoopRunner {

    /** 工具调用轮次上限（§7.2 禁魔法值）：防止模型无限调工具。 */
    public static final int MAX_TOOL_ROUNDS = 3;

    private static final Logger log = LoggerFactory.getLogger(ToolLoopRunner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ProviderRouter providerRouter;

    public ToolLoopRunner(ProviderRouter providerRouter) {
        this.providerRouter = providerRouter;
    }

    /** 循环结果：模型最后一轮的回答正文 + 拼好工具结果的消息列表（供最终流式生成复用）。 */
    public record LoopResult(String finalAnswer, List<ChatMessage> messages) {
    }

    /**
     * 跑「思考→执行→反思」循环（M8/T3，流式）。
     *
     * @param ctx          编排上下文（trace 累积进 {@code ctx.getTrace()}）
     * @param tools        本轮可用工具（MCP 适配器 + 内置 current-time）
     * @param usage        token 用量收集（跨轮累加）
     * @param seedMessages 初始消息列表（system / user / history）
     * @param stepSink     进度事件出口（{@code ConversationService} 包成 sendStep(kind="tool")）
     * @param tokenSink    内容增量出口（每轮 LLM 生成的字经此实时推给前端，实现真流式；不传可 null）
     * @return 循环结果（最终答案 + 拼好工具结果的消息列表）
     */
    public LoopResult run(ChatContext ctx, List<Tool> tools, TokenUsage usage,
                          List<ChatMessage> seedMessages, ToolStepSink stepSink, Consumer<String> tokenSink) {
        List<ToolDefinition> toolDefs = tools.stream().map(Tool::getDefinition).toList();
        Map<String, Tool> byName = new LinkedHashMap<>();
        for (Tool t : tools) {
            byName.put(t.getDefinition().name(), t);
        }
        List<ChatMessage> messages = new ArrayList<>(seedMessages);
        String finalAnswer = "";

        for (int round = 1; round <= MAX_TOOL_ROUNDS; round++) {
            // 改用流式带工具调用：LLM 生成过程中每个字实时经 tokenSink 推给前端（修复 KB 后长时间空白的体验问题）
            LlmResponse resp = providerRouter.routeWithToolsStream(messages, ctx.getProviderRef(), toolDefs, tokenSink);
            accumulate(usage, resp);

            List<ToolCall> calls = resp.getToolCalls();
            if (calls == null || calls.isEmpty()) {
                // 模型不再要工具 → 跳出，用本轮正文作答
                finalAnswer = resp.getContent() == null ? "" : resp.getContent();
                return new LoopResult(finalAnswer, messages);
            }

            // 1) 把模型这轮（带 tool_calls 的 assistant 消息）追加进上下文
            ChatMessage assistantMsg = new ChatMessage("assistant", resp.getContent());
            assistantMsg.setToolCalls(calls);
            messages.add(assistantMsg);

            // 2) 逐个执行工具
            for (ToolCall tc : calls) {
                String fn = tc.functionName();
                String argsJson = tc.argumentsJson() == null ? "{}" : tc.argumentsJson();
                stepSink.step("正在调用工具：" + fn, "running");
                long startMs = System.currentTimeMillis();
                ToolResult result;
                Tool tool = byName.get(fn);
                if (tool == null) {
                    log.warn("tool loop unknown tool={} round={}", fn, round);
                    result = ToolResult.fail("未知工具：" + fn);
                } else {
                    // === M10/T5 执行闸：L2/L3 一期默认拒绝（§2 模块8 / §4.5 降级纪律） ===
                    RiskLevel level = tool.riskLevel();
                    if (level == RiskLevel.L2_IRREVERSIBLE || level == RiskLevel.L3_HIGH_RISK) {
                        String blockMsg = "工具「" + fn + "」风险等级为 " + level + "（" + level.desc
                                + "），需管理员确认，当前对话模式不支持自动执行。已拦截本次调用，未实际执行。";
                        log.info("tool loop risk gate blocked tool={} level={} round={}", fn, level, round);
                        // 一期策略：不进 execute，把提示作为 tool 角色消息塞回，让模型转告用户、循环继续（不抛异常）
                        ctx.getTrace().add(new CallTrace("tool",
                                "调用 " + fn + " 被风险闸拦截（" + level + "）",
                                "blocked", null, null, fn, argsJson, "", false));
                        stepSink.step("工具 " + fn + " 被风险闸拦截（" + level + "）", "done");
                        ChatMessage blockedMsg = new ChatMessage("tool", blockMsg);
                        blockedMsg.setToolCallId(tc.id());
                        messages.add(blockedMsg);
                        continue;
                    }
                    try {
                        result = tool.execute(parseArgs(argsJson));
                    } catch (Exception e) {
                        // 防御：工具执行绝不向上抛（§4.5），内部兜底降级
                        log.warn("tool loop execute failed tool={} round={}", fn, round, e);
                        result = ToolResult.fail("工具执行异常：" + e.getMessage());
                    }
                }
                long costMs = System.currentTimeMillis() - startMs;

                String traceResult = result.content() == null ? "" : result.content();
                if (traceResult.length() > 200) {
                    traceResult = traceResult.substring(0, 200) + "…";
                }
                // 记调用轨迹（入参/出参/状态），满足对话日志铁律（即便失败也留痕）
                ctx.getTrace().add(new CallTrace("tool",
                        "调用 " + fn + (result.success() ? " 成功" : " 失败"),
                        "done", null, null, fn, argsJson, traceResult, result.success()));
                stepSink.step("工具 " + fn + " 返回", "done");
                log.info("tool.call round={} name={} success={} costMs={}", round, fn, result.success(), costMs);

                // 3) 把工具结果作为 tool 角色消息追加（带 tool_call_id，OpenAI 协议要求）
                ChatMessage toolMsg = new ChatMessage("tool",
                        result.success() ? (result.content() == null ? "" : result.content())
                                : ("工具调用失败：" + result.errorMessage()));
                toolMsg.setToolCallId(tc.id());
                messages.add(toolMsg);
            }
        }
        // 超过 MAX_TOOL_ROUNDS：用最后一轮内容兜底（不再调工具，避免无限循环）
        return new LoopResult(finalAnswer, messages);
    }

    /** 跨轮累加 token 用量（null 字段按 0 计；{@link TokenUsage#setUsage} 仅在 &gt;0 时覆盖）。 */
    private void accumulate(TokenUsage usage, LlmResponse resp) {
        int addPrompt = resp.getPromptTokens() == null ? 0 : resp.getPromptTokens();
        int addCompletion = resp.getCompletionTokens() == null ? 0 : resp.getCompletionTokens();
        usage.setUsage(usage.getPromptTokens() + addPrompt,
                usage.getCompletionTokens() + addCompletion);
    }

    /** 把工具参数 JSON 串解析成 Map；空/非法兜底空 Map（§7.3 前置防御，避免炸）。 */
    private Map<String, Object> parseArgs(String json) {
        try {
            if (json == null || json.isBlank()) {
                return Map.of();
            }
            return MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
