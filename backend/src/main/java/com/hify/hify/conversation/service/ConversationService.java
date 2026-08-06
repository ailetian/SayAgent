package com.hify.hify.conversation.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hify.hify.agent.dto.AgentVO;
import com.hify.hify.agent.service.AgentService;
import com.hify.hify.common.exception.BizException;
import com.hify.hify.common.exception.ErrorCode;
import com.hify.hify.conversation.ConversationLogAsyncWriter;
import com.hify.hify.conversation.ChatContext;
import com.hify.hify.conversation.ChatContext.CallTrace;
import com.hify.hify.conversation.dto.ChatHistoryPage;
import com.hify.hify.conversation.dto.ChatRequest;
import com.hify.hify.conversation.dto.LogRecord;
import com.hify.hify.conversation.entity.Conversation;
import com.hify.hify.conversation.entity.Message;
import com.hify.hify.conversation.repository.ConversationRepository;
import com.hify.hify.conversation.repository.MessageRepository;
import com.hify.hify.conversation.web.ChatMessageVO;
import com.hify.hify.conversation.web.ConversationVO;
import com.hify.hify.knowledge.entity.Document;
import com.hify.hify.knowledge.repository.DocumentRepository;
import com.hify.hify.knowledge.retriever.RetrievalPort;
import com.hify.hify.user.UserService;
import com.hify.hify.modelprovider.client.ChatMessage;
import com.hify.hify.modelprovider.client.TokenUsage;
import com.hify.hify.modelprovider.dto.ModelProviderVO;
import com.hify.hify.modelprovider.service.LlmStreamService;
import com.hify.hify.modelprovider.service.ModelService;
import com.hify.hify.mcp.McpService;
import com.hify.hify.mcp.dto.McpToolCallResult;
import com.hify.hify.mcp.dto.ToolDefinition;
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
    /** 知识召回 topK（T3 默认 4）。 */
    private static final int RETRIEVE_TOP_K = 4;
    /** 知识召回相似度阈值（低于此分不纳入上下文）。 */
    private static final double RETRIEVE_THRESHOLD = 0.6;

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final UserService userService;
    private final ExecutorService sseExecutor;
    private final AgentService agentService;
    private final ModelService modelService;
    private final LlmStreamService llmStreamService;
    private final RetrievalPort retrievalPort;
    private final DocumentRepository documentRepository;
    private final ConversationLogAsyncWriter conversationLogAsyncWriter;
    private final McpService mcpService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ConversationService(ConversationRepository conversationRepository,
                               MessageRepository messageRepository,
                               UserService userService,
                               @Qualifier("sseExecutor") ExecutorService sseExecutor,
                               AgentService agentService,
                               ModelService modelService,
                               LlmStreamService llmStreamService,
                               RetrievalPort retrievalPort,
                               DocumentRepository documentRepository,
                               ConversationLogAsyncWriter conversationLogAsyncWriter,
                               McpService mcpService) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.userService = userService;
        this.sseExecutor = sseExecutor;
        this.agentService = agentService;
        this.modelService = modelService;
        this.llmStreamService = llmStreamService;
        this.retrievalPort = retrievalPort;
        this.documentRepository = documentRepository;
        this.conversationLogAsyncWriter = conversationLogAsyncWriter;
        this.mcpService = mcpService;
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
        Future<?> future = sseExecutor.submit(
                () -> orchestrate(emitter, req, userId, conv, convId, assistantMsgId, disposableRef));
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
            // M7/T3：若 Agent 配置了 toolRefs，调 MCP 工具把结果拼回上下文继续生成（§2 模块8）
            // 用独立 final 变量承接，避免重赋值 ctx 导致 lambda 捕获「非 effectively final」编译错误（§4.2）
            ChatContext enrichedCtx = enrichWithMcpTools(ctx, emitter);
            TokenUsage usage = new TokenUsage();
            StringBuilder answer = new StringBuilder();
            Disposable d = llmStreamService.stream(enrichedCtx.getMessages(), enrichedCtx.getProviderRef(), usage)
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
                                    finalizeAssistantFailed(assistantMsgId, convId, partial, enrichedCtx);
                                } catch (Exception ex) {
                                    log.warn("finalize assistant failed error-path convId={}", conversationId, ex);
                                }
                                try {
                                    writeLog(enrichedCtx, partial, usage, true, start, false, err.getMessage());
                                } catch (Exception ex) {
                                    log.warn("write log error-path convId={}", conversationId, ex);
                                }
                                sendError(emitter, err.getMessage());
                                closeQuietly(emitter);
                            },
                            () -> {
                                String full = answer.toString();
                                int inTok = usage.getPromptTokens() > 0 ? usage.getPromptTokens()
                                        : estimateTokens(enrichedCtx.getMessages());
                                int outTok = usage.getCompletionTokens() > 0 ? usage.getCompletionTokens()
                                        : estimateTokens(full);
                                // 先落库/写日志（确保前端 loadHistory 重载时一定已读到完整回答，避免「回复一闪而过」）；
                                // 再发 done 终结帧并关闭流（前端必定解除流式锁定）。二者任一异常仅告警，绝不阻断前端。
                                try {
                                    finalizeAssistantOk(assistantMsgId, convId, full, enrichedCtx, outTok);
                                } catch (Exception ex) {
                                    log.warn("finalize assistant ok convId={} failed (answer already sent to client)", conversationId, ex);
                                }
                                try {
                                    writeLog(enrichedCtx, full, usage, false, start, true, null);
                                } catch (Exception ex) {
                                    log.warn("write log convId={} failed", conversationId, ex);
                                }
                                sendDone(emitter, enrichedCtx, inTok, outTok, false);
                                closeQuietly(emitter);
                            }
                    );
            // 记下订阅句柄，供 §4.6 SSE 断连时取消（不白烧 token）
            disposableRef.set(d);
        } catch (Exception e) {
            log.warn("chat orchestrate failed convId={}", conversationId, e);
            sendError(emitter, e.getMessage());
            closeQuietly(emitter);
        }
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

        ChatContext ctx = ChatContext.builder()
                .userId(userId)
                .conversationId(conversationId)
                .agentIdStr(agentIdStr)
                .agentDbId(agentDbId)
                .agentName(agent.name())
                .systemPrompt(agent.systemPrompt())
                .providerRef(providerRef)
                .providerType(providerType)
                .model(model)
                .knowledgeRefs(knowledgeRefs)
                .toolRefs(toolRefs)
                .question(req.message())
                .history(history)
                .build();
        boolean hasKB = ctx.getKnowledgeRefs() != null && !ctx.getKnowledgeRefs().isEmpty();
        if (hasKB) sendStep(emitter, "正在检索知识库…", "running", "retrieval");
        List<RetrievalPort.RetrievedChunk> hits = retrieveKnowledge(ctx);
        String knowledge = toKnowledgeText(hits);
        if (hasKB) {
            sendStep(emitter, knowledge != null && !knowledge.isEmpty()
                    ? "知识库检索完成，已获取相关资料" : "知识库检索完成（无命中片段）", "done", "retrieval");
        }
        // 记录知识库检索轨迹：每条命中都留痕（含 docId / 相似度 / 片段摘要）；即便无命中也记一条，
        // 满足对话日志铁律「KB 调用记录必须持久化、可事后回看，即便模型最终未引用也要留痕」。
        if (hits.isEmpty()) {
            ctx.getTrace().add(new CallTrace("retrieval", "知识库检索完成（无命中片段）",
                    "done", null, null, null, null, null, true));
        } else {
            for (RetrievalPort.RetrievedChunk h : hits) {
                String snippet = h.content() == null ? "" : h.content();
                if (snippet.length() > 200) snippet = snippet.substring(0, 200) + "…";
                ctx.getTrace().add(new CallTrace("retrieval",
                        "知识库命中：文档 " + h.documentId() + " 片段#" + h.chunkIndex()
                                + "（相似度 " + String.format("%.3f", h.score()) + "）",
                        "done", h.documentId(), h.score(), null, null, snippet, true));
            }
        }
        ctx = ctx.withRetrievedKnowledge(knowledge);
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
     * 召回知识（带 fallback）：任一切库失败都跳过该库；全部不可用也不阻断 LLM 直接答（§3.3 / T3 验收点3）。
     * 多库结果合并后按相似度降序返回全部命中（topK 截断留给 {@link #toKnowledgeText}）。
     *
     * @return 命中片段列表（含相似度），无命中或不可用返回空列表，不返回 null。
     */
    private List<RetrievalPort.RetrievedChunk> retrieveKnowledge(ChatContext ctx) {
        if (ctx.getKnowledgeRefs().isEmpty()) {
            return List.of();
        }
        try {
            List<RetrievalPort.RetrievedChunk> hits = new ArrayList<>();
            for (Long kbId : ctx.getKnowledgeRefs()) {
                try {
                    // K11 缺陷 A：先取本库未软删的文档业务 id，下推 PG 用 document_id IN(...) 过滤孤儿 chunk
                    List<String> allowedDocIds = documentRepository.findByKbId(kbId).stream()
                            .map(Document::getDocumentId).toList();
                    hits.addAll(retrievalPort.retrieve(ctx.getQuestion(), allowedDocIds, RETRIEVE_TOP_K, RETRIEVE_THRESHOLD));
                } catch (Exception e) {
                    log.warn("knowledge retrieve failed for kbId={}, skip", kbId, e);
                }
            }
            hits.sort(Comparator.comparingDouble(RetrievalPort.RetrievedChunk::score).reversed());
            return hits;
        } catch (Exception e) {
            log.warn("knowledge retrieval unavailable, continue without context", e);
            return List.of();
        }
    }

    /** 把命中片段拼成塞进 system 提示的知识文本（多库合并后取 topK）。 */
    private String toKnowledgeText(List<RetrievalPort.RetrievedChunk> hits) {
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

    /**
     * M7/T3：按 Agent.toolRefs 调 MCP 工具，把结果拼回上下文继续生成（§2 模块8）。
     *
     * <p>大白话：Agent 挂了哪些「外部系统的手」(MCP server id)，就在这里逐个叫外援——先发现工具有哪些，
     * 再调第一个可用工具拿结果，把结果作为一条 system 消息追加进发给 LLM 的 messages。任一 server 失败
     * 只降级成「工具暂时不可用」提示，绝不抛异常中断对话（§4.5）；本方法在事务外执行（orchestrate 跑在 sseExecutor）。
     *
     * @param ctx 已组装的编排上下文
     * @return 可能追加了工具结果消息的上下文
     */
    private ChatContext enrichWithMcpTools(ChatContext ctx, SseEmitter emitter) {
        List<Long> toolRefs = ctx.getToolRefs();
        if (toolRefs == null || toolRefs.isEmpty()) {
            return ctx;
        }
        StringBuilder toolCtx = new StringBuilder();
        for (Long serverId : toolRefs) {
            try {
                sendStep(emitter, "正在调用 MCP 工具：server #" + serverId, "running", "tool");
                List<ToolDefinition> tools = mcpService.listTools(serverId);
                if (tools == null || tools.isEmpty()) {
                    toolCtx.append("\n- [MCP server ").append(serverId).append("] 未发现可用工具");
                    sendStep(emitter, "MCP server #" + serverId + "：未发现可用工具", "done", "tool");
                    ctx.getTrace().add(new CallTrace("tool",
                            "MCP server #" + serverId + "：未发现可用工具", "done",
                            null, null, null, null, null, false));
                    continue;
                }
                // 简单场景取第一个工具调用（生产可交由模型按意图选工具，超出 T3 范围）；参数用用户问题
                ToolDefinition td = tools.get(0);
                String argsJson = buildMcpArgs(ctx.getQuestion());
                McpToolCallResult res = mcpService.callTool(serverId, td.name(), argsJson);
                String full = res.result() == null ? "" : res.result();
                String snippet = full.length() > 200 ? full.substring(0, 200) + "…" : full;
                boolean ok = !(res.fallback() || !res.success());
                if (!ok) {
                    toolCtx.append("\n- [MCP server ").append(serverId).append("] 工具暂时不可用");
                    sendStep(emitter, "MCP server #" + serverId + "：工具暂时不可用", "done", "tool");
                } else {
                    toolCtx.append("\n- [MCP server ").append(serverId).append(" / ").append(td.name())
                            .append("] 工具结果：").append(res.result());
                    String stepSnippet = snippet.length() > 60 ? snippet.substring(0, 60) + "…" : snippet;
                    sendStep(emitter, "MCP 工具返回：" + stepSnippet, "done", "tool");
                }
                // 记录 MCP 调用轨迹（入参/出参/状态），满足对话日志铁律
                ctx.getTrace().add(new CallTrace("tool",
                        "MCP " + td.name() + " @server#" + serverId + (ok ? " 成功" : " 不可用"),
                        "done", null, null, td.name(), argsJson, snippet, ok));
            } catch (Exception e) {
                // 防御：即便 McpService 契约保证不抛，也兜底降级，绝不让对话崩（§4.5）
                log.warn("mcp enrich unexpected error serverId={}", serverId, e);
                toolCtx.append("\n- [MCP server ").append(serverId).append("] 工具暂时不可用");
                sendStep(emitter, "MCP server #" + serverId + "：工具暂时不可用", "done", "tool");
                ctx.getTrace().add(new CallTrace("tool",
                        "MCP server #" + serverId + "：调用异常降级", "done",
                        null, null, null, null, e.getMessage(), false));
            }
        }
        if (toolCtx.length() == 0) {
            return ctx;
        }
        log.info("mcp.enrich done serverCount={} hasResult={}", toolRefs.size(),
                toolCtx.indexOf("工具结果") >= 0);
        List<ChatMessage> msgs = new ArrayList<>(ctx.getMessages());
        msgs.add(new ChatMessage("system",
                "以下是可用的外部工具调用结果，请在回答中结合使用：" + toolCtx.toString().strip()));
        return ctx.withMessages(msgs);
    }

    /** 把用户问题包成 MCP 工具的入参 JSON（简单占位：作为 msg 字段；真实场景由模型构造）。 */
    private String buildMcpArgs(String question) {
        try {
            var args = objectMapper.createObjectNode();
            args.put("msg", question == null ? "" : question);
            return objectMapper.writeValueAsString(args);
        } catch (Exception e) {
            log.debug("mcp.buildArgs fallback to empty", e);
            return "{\"msg\":\"\"}";
        }
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

    /** 成功结束：回填 assistant 消息内容/状态/厂商/token，并更新会话计数与时间。 */
    private void finalizeAssistantOk(Long assistantMsgId, Long convId, String full, ChatContext ctx, int outTok) {
        messageRepository.findById(assistantMsgId).ifPresent(m -> {
            m.setContent(full);
            m.setStatus(Message.MessageStatus.SENT);
            m.setProvider(ctx.getProviderType());
            m.setTokens(outTok);
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
                m.getTraceJson()
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
