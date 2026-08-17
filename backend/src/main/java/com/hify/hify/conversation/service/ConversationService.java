package com.hify.hify.conversation.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hify.hify.agent.dto.AgentVO;
import com.hify.hify.agent.service.AgentService;
import com.hify.hify.common.exception.BizException;
import com.hify.hify.skill.service.SkillService;
import com.hify.hify.common.exception.ErrorCode;
import com.hify.hify.conversation.ConversationLogAsyncWriter;
import com.hify.hify.conversation.ChatContext;
import com.hify.hify.conversation.ChatContext.CallTrace;
import com.hify.hify.conversation.tool.ToolLoopRunner;
import com.hify.hify.conversation.tool.ToolRegistry;
import com.hify.hify.common.tool.Tool;
import com.hify.hify.conversation.dto.ChatHistoryPage;
import com.hify.hify.conversation.dto.ChatRequest;
import com.hify.hify.conversation.dto.LogRecord;
import com.hify.hify.conversation.entity.Conversation;
import com.hify.hify.conversation.entity.Message;
import com.hify.hify.conversation.repository.ConversationRepository;
import com.hify.hify.conversation.repository.MessageRepository;
import com.hify.hify.conversation.web.ChatMessageVO;
import com.hify.hify.conversation.web.ConversationVO;
import com.hify.hify.knowledge.retriever.RetrievalResult;
import com.hify.hify.knowledge.service.KbRetrievalService;
import com.hify.hify.knowledge.service.QueryIntentClassifier;
import com.hify.hify.user.UserService;
import com.hify.hify.modelprovider.client.ChatMessage;
import com.hify.hify.modelprovider.client.TokenUsage;
import com.hify.hify.modelprovider.dto.ModelProviderVO;
import com.hify.hify.modelprovider.service.LlmStreamService;
import com.hify.hify.modelprovider.service.ModelService;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 会话编排服务（M6 T2 生命周期 + T3 流式编排）。
 *
 * <p>大白话：这是「聊天主流程」的指挥中心——前端来一句消息，这里负责
 * 解析/新建会话 → 落库 user 消息 → 取 Agent 系统提示 → 召回知识库（失败不影响 LLM）→
 * 流式调 LLM 逐字推给前端 → 结束落库 assistant 消息与流水日志。SSE 长连接走 T2 的
 * {@code sseExecutor} 虚拟线程池，Tomcat 主线程不阻塞（§8 一致性）。
 *
 * <p>流式接口：M3 只发了阻塞式的 {@code ModelService}/{@code ProviderRouter}，
 * T3 用 {@link LlmStreamService}（补 M3 的流式接口）拿到 {@code Flux<String>}，逐片推送。
 */
@Slf4j
@Service
public class ConversationService {

    /** SSE 长连接超时（§4.6 下限 120s；流式生成可能较长，统一 2 分钟）。 */
    private static final long SSE_TIMEOUT_MS = 120_000;
    /** 标题取前 N 字。 */
    private static final int TITLE_PREFIX_LEN = 20;
    /** 历史回放最多带入 N 条（避免超出上下文）。 */
    private static final int MAX_HISTORY = 20;
    /** 知识召回 topK（T3 默认 4，与探针/混合检索对齐）。 */
    private static final int RETRIEVE_TOP_K = 4;
    /** 配置了知识库但本次检索无命中时，直接返回的拒答话术（系统硬开关，不调 LLM 编造）。 */
    private static final String NO_KB_HIT_REPLY =
            "抱歉，我暂时没有查到关于这个问题的相关资料，建议您联系人工客服或换种方式描述需求。";

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final UserService userService;
    private final ExecutorService sseExecutor;
    private final AgentService agentService;
    private final ModelService modelService;
    private final LlmStreamService llmStreamService;
    private final ConversationLogAsyncWriter conversationLogAsyncWriter;
    private final ToolRegistry toolRegistry;
    private final ToolLoopRunner toolLoopRunner;
    private final SkillService skillService;
    private final KbRetrievalService kbRetrievalService;
    private final QueryIntentClassifier intentClassifier;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ConversationService(ConversationRepository conversationRepository,
                               MessageRepository messageRepository,
                               UserService userService,
                               @Qualifier("sseExecutor") ExecutorService sseExecutor,
                               AgentService agentService,
                               ModelService modelService,
                               LlmStreamService llmStreamService,
                               ConversationLogAsyncWriter conversationLogAsyncWriter,
                               ToolRegistry toolRegistry,
                               ToolLoopRunner toolLoopRunner,
                               SkillService skillService,
                               KbRetrievalService kbRetrievalService,
                               QueryIntentClassifier intentClassifier) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.userService = userService;
        this.sseExecutor = sseExecutor;
        this.agentService = agentService;
        this.modelService = modelService;
        this.llmStreamService = llmStreamService;
        this.conversationLogAsyncWriter = conversationLogAsyncWriter;
        this.toolRegistry = toolRegistry;
        this.toolLoopRunner = toolLoopRunner;
        this.skillService = skillService;
        this.kbRetrievalService = kbRetrievalService;
        this.intentClassifier = intentClassifier;
    }

    // ===================== T2 端点支撑 =====================

    public List<ConversationVO> listConversations(String username) {
        Long userId = userService.resolveUserId(username);
        List<Conversation> list = conversationRepository.findByUserIdOrderByLastActiveAtDesc(userId);
        // 置顶优先：pinned=true 排在最前，其次按最后活跃时间倒序
        list.sort(Comparator.comparing(Conversation::getPinned,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Conversation::getLastActiveAt,
                        Comparator.nullsLast(Comparator.reverseOrder())));
        return list.stream().map(this::toVO).toList();
    }

    public ChatHistoryPage listMessages(String conversationId, String username, Long lastId, int size) {
        Long userId = userService.resolveUserId(username);
        Conversation conv = loadOwnedConversation(conversationId, userId);
        int pageSize = Math.min(Math.max(size, 1), 100);
        List<Message> msgs;
        if (lastId == null) {
            msgs = messageRepository.findTop20ByConversationIdOrderByIdDesc(conversationId);
            msgs.sort(Comparator.comparing(Message::getId));
        } else {
            msgs = messageRepository.findByConversationIdAndIdLessThan(conversationId, lastId,
                    PageRequest.of(0, pageSize, Sort.by(Sort.Direction.DESC, "id")));
            msgs.sort(Comparator.comparing(Message::getId));
        }
        boolean hasMore = msgs.size() == pageSize;
        List<ChatMessageVO> items = msgs.stream().map(this::toMessageVO).toList();
        Long nextCursor = hasMore && !msgs.isEmpty() ? msgs.get(0).getId() : null;
        return new ChatHistoryPage(items, nextCursor, hasMore);
    }

    @Transactional
    public void deleteConversation(String conversationId, String username) {
        Long userId = userService.resolveUserId(username);
        Conversation conv = loadOwnedConversation(conversationId, userId);
        conversationRepository.delete(conv);
    }

    /** 重命名会话（须本人）。 */
    @Transactional
    public void renameConversation(String conversationId, String username, String title) {
        Long userId = userService.resolveUserId(username);
        Conversation conv = loadOwnedConversation(conversationId, userId);
        if (title == null || title.trim().isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "标题不能为空");
        }
        conv.setTitle(title.trim());
        conversationRepository.save(conv);
    }

    /** 置顶 / 取消置顶（须本人）。 */
    @Transactional
    public void pinConversation(String conversationId, String username, boolean pinned) {
        Long userId = userService.resolveUserId(username);
        Conversation conv = loadOwnedConversation(conversationId, userId);
        conv.setPinned(pinned);
        conversationRepository.save(conv);
    }

    // ===================== T3 流式编排 =====================

    /**
     * 聊天流式主入口（SSE 长连接）。
     *
     * <p>大白话：先（同步，事务内）建/取会话、落库 user 消息与一条 PENDING 的 assistant 占位，
     * 立刻把 SseEmitter 还给 Tomcat；真正的「取 Agent → 召回 → 流式调 LLM → 落库」放在
     * {@code sseExecutor} 虚拟线程里跑，逐字推给前端，结束再回填 assistant 内容与日志。
     */
    @Transactional
    public SseEmitter stream(ChatRequest req) {
        Long userId = currentUser();
        Conversation conv = resolveConversation(req, userId);
        String conversationId = conv.getConversationId();

        long userSeq = messageRepository.countByConversationId(conversationId) + 1;
        Message userMsg = new Message();
        userMsg.setConversationId(conversationId);
        userMsg.setUserId(userId);
        userMsg.setRole(Message.MessageRole.USER);
        userMsg.setContent(req.message());
        userMsg.setSeq((int) userSeq);
        userMsg.setStatus(Message.MessageStatus.SENT);
        messageRepository.save(userMsg);

        long assistantSeq = userSeq + 1;
        Message assistantMsg = new Message();
        assistantMsg.setConversationId(conversationId);
        assistantMsg.setUserId(userId);
        assistantMsg.setRole(Message.MessageRole.ASSISTANT);
        assistantMsg.setContent("");
        assistantMsg.setSeq((int) assistantSeq);
        assistantMsg.setStatus(Message.MessageStatus.PENDING);
        assistantMsg = messageRepository.save(assistantMsg);

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        Long convId = conv.getId();
        Long assistantMsgId = assistantMsg.getId();

        // §4.6 SSE 取消：断连时既中断编排线程，也直接取消 LLM token 流订阅，避免白烧 token
        AtomicReference<Disposable> disposableRef = new AtomicReference<>();
        // 对话编排跑在虚拟线程（sseExecutor）上；SecurityContext 默认 ThreadLocal 不跨线程传播，
        // 必须显式把主请求线程已校验好的身份带进虚拟线程，否则后续 assertAccessible 等鉴权会误判未认证。
        org.springframework.security.core.context.SecurityContext secCtx = SecurityContextHolder.getContext();
        Future<?> future = sseExecutor.submit(() -> {
            SecurityContextHolder.setContext(secCtx);
            try {
                orchestrate(emitter, req, userId, conv, convId, assistantMsgId, disposableRef);
            } finally {
                SecurityContextHolder.clearContext();
            }
        });
        emitter.onTimeout(() -> {
            log.warn("chat stream timeout convId={}", conversationId);
            cancelStream(future, disposableRef);
        });
        emitter.onError((e) -> {
            log.warn("chat stream error convId={}", conversationId, e);
            cancelStream(future, disposableRef);
        });
        return emitter;
    }

    /** §4.6 SSE 取消：同时中断编排线程并取消 LLM 订阅，确保断连后后台不再继续生成。 */
    private void cancelStream(Future<?> future, AtomicReference<Disposable> disposableRef) {
        if (future != null) {
            future.cancel(true);
        }
        Disposable d = disposableRef.get();
        if (d != null && !d.isDisposed()) {
            d.dispose();
        }
    }

    /**
     * 编排主流程（在 sseExecutor 虚拟线程上执行）。
     * 顺序：组装上下文 → 发 meta → 流式调 LLM（逐 token 推送）→ 结束发 done 并落库；任一异常发 error。
     */
    void orchestrate(SseEmitter emitter, ChatRequest req, Long userId, Conversation conv,
                     Long convId, Long assistantMsgId, AtomicReference<Disposable> disposableRef) {
        String conversationId = conv.getConversationId();
        Instant start = Instant.now();
        try {
            ChatContext ctx = prepare(req, userId, conv, emitter);
            sendMeta(emitter, ctx);
            // 拒答硬开关（知识库兜底）：配置了知识库但本次检索无命中，直接返回固定话术，不再调用 LLM 编造。
            if (ctx.getKnowledgeRefs() != null && !ctx.getKnowledgeRefs().isEmpty()
                    && (ctx.getRetrievedKnowledge() == null || ctx.getRetrievedKnowledge().isBlank())
                    && !ctx.isKbRetrievalSkipped()) {
                sendToken(emitter, NO_KB_HIT_REPLY);
                finalizeStreamedAnswer(emitter, NO_KB_HIT_REPLY, ctx, conversationId, convId,
                        assistantMsgId, new TokenUsage(), start);
                return;
            }
            // M8/T3：解析本轮可用工具（MCP 适配器 + 内置 current-time），跑「思考→执行→反思」循环（流式）
            List<Tool> tools = toolRegistry.resolve(ctx.getToolRefs());
            TokenUsage usage = new TokenUsage();
            ToolLoopRunner.LoopResult loop = toolLoopRunner.run(ctx, tools, usage, ctx.getMessages(),
                    (label, status) -> sendStep(emitter, label, status, "tool"),
                    token -> sendToken(emitter, token));
            // 方案①（M8/T3 性能修复）+ 流式回归修复：工具循环已「边生成边把字经 tokenSink 推给前端」，
            // 不再重新调大模型（避免整段 KB+MCP 上下文重 prefill 的空等），且用户实时看到逐字输出。
            // 循环结束后此处仅做落库 + 发 done（不再切 30 字假流式）。
            String finalAnswer = loop.finalAnswer();
            if (finalAnswer != null && !finalAnswer.isBlank()) {
                finalizeStreamedAnswer(emitter, finalAnswer, ctx, conversationId, convId, assistantMsgId, usage, start);
            } else {
                // 兜底：finalAnswer 为空（如触顶 MAX_TOOL_ROUNDS 仍只要求调工具）才退回现有流式重生成
                StringBuilder answer = new StringBuilder();
                Disposable d = llmStreamService.stream(loop.messages(), ctx.getProviderRef(), usage)
                        .subscribe(
                                token -> {
                                    answer.append(token);
                                    sendToken(emitter, token);
                                },
                                err -> {
                                    String partial = answer.toString();
                                    // 先落库/写日志（确保前端 loadHistory 重载时已读到那条失败回答，避免「回复一闪而过」），
                                    // 再发 error 终结帧并关闭流（前端必定解除流式锁定）。二者任一异常仅告警，绝不阻断。
                                    try {
                                        finalizeAssistantFailed(assistantMsgId, convId, partial, ctx);
                                    } catch (Exception ex) {
                                        log.warn("finalize assistant failed error-path convId={}", conversationId, ex);
                                    }
                                    try {
                                        writeLog(ctx, partial, usage, true, start, false, err.getMessage());
                                    } catch (Exception ex) {
                                        log.warn("write log error-path convId={}", conversationId, ex);
                                    }
                                    sendError(emitter, err.getMessage());
                                    closeQuietly(emitter);
                                },
                                () -> {
                                    String full = answer.toString();
                                    int inTok = usage.getPromptTokens() > 0 ? usage.getPromptTokens()
                                            : estimateTokens(ctx.getMessages());
                                    int outTok = usage.getCompletionTokens() > 0 ? usage.getCompletionTokens()
                                            : estimateTokens(full);
                                    // 先落库/写日志（确保前端 loadHistory 重载时一定已读到完整回答，避免「回复一闪而过」）；
                                    // 再发 done 终结帧并关闭流（前端必定解除流式锁定）。二者任一异常仅告警，绝不阻断前端。
                                    try {
                                        finalizeAssistantOk(assistantMsgId, convId, full, ctx, inTok, outTok);
                                    } catch (Exception ex) {
                                        log.warn("finalize assistant ok convId={} failed (answer already sent to client)", conversationId, ex);
                                    }
                                    try {
                                        writeLog(ctx, full, usage, false, start, true, null);
                                    } catch (Exception ex) {
                                        log.warn("write log convId={} failed", conversationId, ex);
                                    }
                                    sendDone(emitter, ctx, inTok, outTok, false);
                                    closeQuietly(emitter);
                                }
                        );
                // 记下订阅句柄，供 §4.6 SSE 断连时取消（不白烧 token）
                disposableRef.set(d);
            }
        } catch (Exception e) {
            log.warn("chat orchestrate failed convId={}", conversationId, e);
            sendError(emitter, e.getMessage());
            closeQuietly(emitter);
        }
    }

    /**
     * 工具循环已把答案逐字流式推给前端，此处仅做落库 + 发 done + 关流。
     * 不再切 30 字假流式——真正的逐字输出已在循环里经 tokenSink（sendToken）实时推给前端。
     * 任一异常仅告警，绝不阻断前端（片段已推，用户已看到）。
     */
    private void finalizeStreamedAnswer(SseEmitter emitter, String finalAnswer, ChatContext ctx,
                                        String conversationId, Long convId, Long assistantMsgId,
                                        TokenUsage usage, Instant start) {
        int inTok = usage.getPromptTokens() > 0 ? usage.getPromptTokens() : estimateTokens(ctx.getMessages());
        int outTok = usage.getCompletionTokens() > 0 ? usage.getCompletionTokens() : estimateTokens(finalAnswer);
        try {
            finalizeAssistantOk(assistantMsgId, convId, finalAnswer, ctx, inTok, outTok);
        } catch (Exception ex) {
            log.warn("finalize assistant ok (streamed) convId={} failed", conversationId, ex);
        }
        try {
            writeLog(ctx, finalAnswer, usage, false, start, true, null);
        } catch (Exception ex) {
            log.warn("write log (streamed) convId={} failed", conversationId, ex);
        }
        sendDone(emitter, ctx, inTok, outTok, false);
        closeQuietly(emitter);
    }

    /** 组装编排上下文：解析 Agent → 取厂商配置 → 召回知识 → 拼最终 messages。 */
    private ChatContext prepare(ChatRequest req, Long userId, Conversation conv, SseEmitter emitter) {
        String conversationId = conv.getConversationId();
        String agentIdStr = req.agentId();
        Long agentDbId = parseAgentId(agentIdStr);
        AgentVO agent = agentService.getAgent(agentDbId);
        Long providerRef = agent.modelProviderId();
        ModelProviderVO provider = modelService.getProvider(providerRef);
        String providerType = provider.providerType().name();
        String model = provider.model();
        List<ChatMessage> history = loadHistory(conversationId);
        List<Long> knowledgeRefs = agent.knowledgeRefs() == null ? List.of() : agent.knowledgeRefs();
        List<Long> toolRefs = agent.toolRefs() == null ? List.of() : agent.toolRefs();
        List<Long> skillRefs = agent.skillRefs() == null ? List.of() : agent.skillRefs();

        ChatContext ctx = ChatContext.builder()
                .userId(userId)
                .conversationId(conversationId)
                .agentIdStr(agentIdStr)
                .agentDbId(agentDbId)
                .agentName(agent.name())
                // M8/T4：把 Agent 挂载的技能提示词拼进人设（提示词型 skill，配置时静态组合）
                .systemPrompt(skillService.composePersona(agent.systemPrompt(), skillRefs))
                .providerRef(providerRef)
                .providerType(providerType)
                .model(model)
                .knowledgeRefs(knowledgeRefs)
                .toolRefs(toolRefs)
                .skillRefs(skillRefs)
                .question(req.message())
                .history(history)
                .build();
        boolean hasKB = ctx.getKnowledgeRefs() != null && !ctx.getKnowledgeRefs().isEmpty();
        // 意图网关：复用 QueryIntentClassifier，非 QUESTION（问候/问身份/无意义/动作占位）直接跳过知识库检索，
        // 避免「你好」这类问候被无谓检索、且被 orchestrate 兜底误答成「找不到资料」。与 RagQueryService 的意图网关保持一致。
        QueryIntentClassifier.Intent intent = intentClassifier.classify(ctx.getQuestion());
        boolean skipKb = hasKB && intent != QueryIntentClassifier.Intent.QUESTION;
        ctx = ctx.withKbRetrievalSkipped(skipKb);
        List<RetrievalResult> hits = List.of();
        if (hasKB && !skipKb) {
            sendStep(emitter, "正在检索知识库…", "running", "retrieval");
            hits = retrieveKnowledge(ctx);
            String knowledge = toKnowledgeText(hits);
            sendStep(emitter, knowledge != null && !knowledge.isEmpty()
                    ? "知识库检索完成，已获取相关资料" : "知识库检索完成（无命中片段）", "done", "retrieval");
            // 记录知识库检索轨迹：仅 hasKB 且实际检索才写轨迹，满足对话日志铁律
            // 「KB 调用记录必须持久化、可事后回看」。意图跳过检索的不写任何 KB 轨迹，避免"假调用"误导。
            if (hits.isEmpty()) {
                ctx.getTrace().add(new CallTrace("retrieval", "知识库检索完成（无命中片段）",
                        "done", null, null, null, null, null, true));
            } else {
                for (RetrievalResult h : hits) {
                    String snippet = h.content() == null ? "" : h.content();
                    if (snippet.length() > 200) snippet = snippet.substring(0, 200) + "…";
                    ctx.getTrace().add(new CallTrace("retrieval",
                            "知识库命中：文档 " + h.documentId() + " 片段#" + h.chunkIndex()
                                    + "（相似度 " + String.format("%.3f", h.semanticScore()) + "）",
                            "done", h.documentId(), h.semanticScore(), null, null, snippet, true));
                }
            }
            ctx = ctx.withRetrievedKnowledge(knowledge);
        }
        ctx = ctx.withMessages(buildMessages(ctx));
        return ctx;
    }

    private Long parseAgentId(String agentIdStr) {
        if (agentIdStr == null || agentIdStr.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "agentId 不能为空");
        }
        try {
            return Long.parseLong(agentIdStr.trim());
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "agentId 非法: " + agentIdStr);
        }
    }

    /** 加载历史消息（最近 N 条，转成 LLM ChatMessage）。 */
    private List<ChatMessage> loadHistory(String conversationId) {
        List<Message> msgs = messageRepository.findByConversationIdOrderBySeqAsc(conversationId);
        int from = Math.max(0, msgs.size() - MAX_HISTORY);
        List<ChatMessage> history = new ArrayList<>();
        for (Message m : msgs.subList(from, msgs.size())) {
            history.add(new ChatMessage(m.getRole().name().toLowerCase(), m.getContent()));
        }
        return history;
    }

    /**
     * 召回知识（统一走 KbRetrievalService 唯一入口，与探针 / ask / eval 行为完全一致）：
     * 逐个挂载库按「库级生效配置」跑混合检索，阈值取自该库配置而非写死常量，
     * 多库结果合并后按语义余弦相似度（semanticScore）降序返回（topK 截断留给 {@link #toKnowledgeText}）。
     * 任一切库失败都跳过该库；全部不可用也不阻断 LLM 直接答（§3.3 / T3 验收点3）。
     *
     * @return 命中片段列表（含语义相似度），无命中或不可用返回空列表，不返回 null。
     */
    private List<RetrievalResult> retrieveKnowledge(ChatContext ctx) {
        if (ctx.getKnowledgeRefs().isEmpty()) {
            return List.of();
        }
        try {
            List<RetrievalResult> hits = new ArrayList<>();
            for (Long kbId : ctx.getKnowledgeRefs()) {
                try {
                    // 与探针完全一致：阈值来自库级生效配置，库不存在抛异常被此处捕获并跳过
                    List<RetrievalResult> kbHits = kbRetrievalService.retrieve(kbId, ctx.getQuestion());
                    log.info("kbId={} retrieve hits={}", kbId, kbHits.size());
                    hits.addAll(kbHits);
                } catch (Exception e) {
                    log.warn("knowledge retrieve failed for kbId={}, skip", kbId, e);
                }
            }
            hits.sort(Comparator.comparingDouble(RetrievalResult::semanticScore).reversed());
            return hits;
        } catch (Exception e) {
            log.warn("knowledge retrieval unavailable, continue without context", e);
            return List.of();
        }
    }

    /** 把命中片段拼成塞进 system 提示的知识文本（多库合并后取 topK）。 */
    private String toKnowledgeText(List<RetrievalResult> hits) {
        if (hits.isEmpty()) {
            return "";
        }
        int take = Math.min(RETRIEVE_TOP_K, hits.size());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < take; i++) {
            sb.append("- ").append(hits.get(i).content()).append("\n");
        }
        return sb.toString().strip();
    }

    /** 拼最终发给 LLM 的 messages：system(含召回知识) + 历史 + 当前 user 问题。 */
    private List<ChatMessage> buildMessages(ChatContext ctx) {
        List<ChatMessage> msgs = new ArrayList<>();
        String system = ctx.getSystemPrompt();
        if (ctx.getRetrievedKnowledge() != null && !ctx.getRetrievedKnowledge().isBlank()) {
            system = system + "\n\n参考知识库内容：\n" + ctx.getRetrievedKnowledge();
        }
        msgs.add(new ChatMessage("system", system));
        msgs.addAll(ctx.getHistory());
        msgs.add(new ChatMessage("user", ctx.getQuestion()));
        return msgs;
    }

    /** 成功结束：回填 assistant 消息内容/状态/厂商/model/token（输入+输出），并更新会话计数与时间。 */
    private void finalizeAssistantOk(Long assistantMsgId, Long convId, String full, ChatContext ctx, int inTok, int outTok) {
        messageRepository.findById(assistantMsgId).ifPresent(m -> {
            m.setContent(full);
            m.setStatus(Message.MessageStatus.SENT);
            m.setProvider(ctx.getProviderType());
            m.setModel(ctx.getModel());
            m.setTokens(outTok);
            m.setTokensIn(inTok);
            m.setTraceJson(writeTrace(ctx));
            messageRepository.save(m);
        });
        conversationRepository.findById(convId).ifPresent(c -> {
            c.setMessageCount(messageRepository.countByConversationId(c.getConversationId()));
            c.setLastActiveAt(Instant.now());
            conversationRepository.save(c);
        });
    }

    /** 失败结束：assistant 消息置 FAILED（保留已生成片段），仅刷新会话活跃时间。 */
    private void finalizeAssistantFailed(Long assistantMsgId, Long convId, String partial, ChatContext ctx) {
        messageRepository.findById(assistantMsgId).ifPresent(m -> {
            m.setContent(partial);
            m.setStatus(Message.MessageStatus.FAILED);
            m.setProvider(ctx.getProviderType());
            m.setModel(ctx.getModel());
            m.setTokens(estimateTokens(partial));
            m.setTraceJson(writeTrace(ctx));
            messageRepository.save(m);
        });
        conversationRepository.findById(convId).ifPresent(c -> {
            c.setLastActiveAt(Instant.now());
            conversationRepository.save(c);
        });
    }

    /** 写对话流水日志（异步，不阻塞 SSE 主链路）：组装 LogRecord 丢给 T4 异步门面即返回。 */
    private void writeLog(ChatContext ctx, String answer, TokenUsage usage, boolean fallback,
                          Instant start, boolean success, String errorMsg) {
        int inTok = usage.getPromptTokens() > 0 ? usage.getPromptTokens() : estimateTokens(ctx.getMessages());
        int outTok = usage.getCompletionTokens() > 0 ? usage.getCompletionTokens() : estimateTokens(answer);
        LogRecord rec = new LogRecord();
        rec.setUserId(ctx.getUserId());
        rec.setAgentId(ctx.getAgentIdStr());
        rec.setConversationId(ctx.getConversationId());
        rec.setQuestion(ctx.getQuestion());
        rec.setInTok(inTok);
        rec.setOutTok(outTok);
        rec.setProvider(ctx.getProviderType());
        rec.setModel(ctx.getModel());
        rec.setFallback(fallback);
        conversationLogAsyncWriter.log(rec);
        log.debug("conversation_log submitted success={} fallback={} costMs={}", success, fallback,
                Duration.between(start, Instant.now()).toMillis());
    }

    /** 把编排过程累积的调用轨迹序列化为 JSON（落库 message.trace_json）。序列化失败降级为空数组，绝不阻断落库。 */
    private String writeTrace(ChatContext ctx) {
        try {
            return objectMapper.writeValueAsString(ctx.getTrace());
        } catch (Exception e) {
            log.debug("trace serialize failed convId={}", ctx.getConversationId(), e);
            return "[]";
        }
    }

    // ===================== SSE 事件发送 =====================

    private void sendMeta(SseEmitter emitter, ChatContext ctx) {
        send(emitter, new ChatEvent("meta", ctx.getConversationId(), Instant.now().toString(),
                null, null, null, null, null, null, null, null, null));
    }

    private void sendToken(SseEmitter emitter, String content) {
        send(emitter, new ChatEvent("token", null, null, content, null, null, null, null, null, null, null, null));
    }

    private void sendStep(SseEmitter emitter, String label, String status, String kind) {
        send(emitter, new ChatEvent("step", null, null, label, null, null, null, null, null, null, kind, status));
    }

    private void sendDone(SseEmitter emitter, ChatContext ctx, int inTok, int outTok, boolean fallback) {
        send(emitter, new ChatEvent("done", null, null, null, inTok, outTok,
                ctx.getProviderType(), ctx.getModel(), fallback, null, null, null));
    }

    private void sendError(SseEmitter emitter, String message) {
        send(emitter, new ChatEvent("error", null, null, null, null, null, null, null, null, message, null, null));
    }

    void send(SseEmitter emitter, ChatEvent event) {
        try {
            emitter.send(SseEmitter.event().data(event));
        } catch (IOException e) {
            log.debug("sse send failed (client disconnected?) event={}", event.event(), e);
        }
    }

    private void closeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignore) {
            // 客户端已断开，忽略
        }
    }

    // ===================== 工具 =====================

    /** 中文/混合文本粗略 token 估算：按字符数 /4（无 tokenizer 时的可接受近似，仅用于用量展示与降级计量）。 */
    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.max(1, text.length() / 4);
    }

    private int estimateTokens(List<ChatMessage> messages) {
        int total = 0;
        for (ChatMessage m : messages) {
            total += m.getContent() == null ? 0 : m.getContent().length();
        }
        return Math.max(1, total / 4);
    }

    private Conversation resolveConversation(ChatRequest req, Long userId) {
        if (req.conversationId() != null && !req.conversationId().isBlank()) {
            return loadOwnedConversation(req.conversationId(), userId);
        }
        Conversation conv = new Conversation();
        conv.setUserId(userId);
        conv.setConversationId("conv-" + UUID.randomUUID());
        conv.setAgentId(req.agentId());
        conv.setTitle(titleOf(req.message()));
        conv.setStatus(Conversation.ConversationStatus.ACTIVE);
        conv.setMessageCount(0L);
        conv.setLastActiveAt(Instant.now());
        conversationRepository.save(conv);
        return conv;
    }

    private Conversation loadOwnedConversation(String conversationId, Long userId) {
        return conversationRepository.findByConversationIdAndUserId(conversationId, userId)
                .orElseThrow(() -> new BizException(ErrorCode.RESOURCE_NOT_FOUND,
                        "会话不存在或无权访问: " + conversationId));
    }

    private Long currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "未登录");
        }
        return userService.resolveUserId(auth.getName());
    }

    private String titleOf(String message) {
        if (message == null) {
            return "";
        }
        return message.length() <= TITLE_PREFIX_LEN ? message : message.substring(0, TITLE_PREFIX_LEN) + "…";
    }

    private ConversationVO toVO(Conversation c) {
        return new ConversationVO(
                c.getConversationId(),
                c.getTitle(),
                c.getAgentId(),
                c.getMessageCount() == null ? 0L : c.getMessageCount(),
                c.getStatus() == null ? null : c.getStatus().name(),
                c.getPinned(),
                c.getLastActiveAt(),
                c.getCreatedAt() == null ? null : c.getCreatedAt().toInstant(ZoneOffset.UTC)
        );
    }

    private ChatMessageVO toMessageVO(Message m) {
        return new ChatMessageVO(
                m.getId(),
                m.getConversationId(),
                m.getRole() == null ? null : m.getRole().name(),
                m.getContent(),
                m.getSeq(),
                m.getCreatedAt() == null ? null : m.getCreatedAt().toInstant(ZoneOffset.UTC),
                m.getTraceJson(),
                m.getTokensIn(),
                m.getTokens(),
                m.getProvider(),
                m.getModel(),
                null
        );
    }

    /**
     * SSE 事件载荷（强类型事件对象，避免 SSE 序列化时散落裸字段）。
     *
     * <p>大白话：每个事件就是一个 JSON 对象，前端按 {@code event} 字段分流——
     * meta（首帧，流式才发）/ token（逐字）/ step（进度：retrieval 知识库检索 / tool MCP 工具调用）/ done（结束，含 token 统计）/ error（失败）。
     * {@code @JsonInclude(NON_NULL)} 保证只序列化非空的字段，线格式即 {@code {"event":"token","content":"..."}}。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ChatEvent(
            String event,
            String conversationId,
            String createdAt,
            String content,
            Integer inTok,
            Integer outTok,
            String provider,
            String model,
            Boolean fallback,
            String message,
            /** 步骤事件类型：retrieval=知识库检索 / tool=MCP 工具调用（前端进度条用）。 */
            String kind,
            /** 步骤状态：running=进行中 / done=完成（前端进度条用）。 */
            String stepStatus
    ) {
    }
}
