package com.hify.hify.conversation.service;

import com.hify.hify.agent.dto.AgentVO;
import com.hify.hify.agent.service.AgentService;
import com.hify.hify.common.exception.BizException;
import com.hify.hify.common.exception.ErrorCode;
import com.hify.hify.conversation.ConversationLogAsyncWriter;
import com.hify.hify.conversation.dto.ChatRequest;
import com.hify.hify.conversation.dto.LogRecord;
import com.hify.hify.conversation.entity.Conversation;
import com.hify.hify.conversation.entity.Message;
import com.hify.hify.conversation.repository.ConversationRepository;
import com.hify.hify.conversation.repository.MessageRepository;
import com.hify.hify.knowledge.retriever.RetrievalResult;
import com.hify.hify.knowledge.service.KbRetrievalService;
import com.hify.hify.knowledge.service.QueryIntentClassifier;
import com.hify.hify.skill.service.SkillService;
import com.hify.hify.conversation.tool.ToolLoopRunner;
import com.hify.hify.conversation.tool.ToolRegistry;
import com.hify.hify.modelprovider.client.ChatMessage;
import com.hify.hify.modelprovider.domain.enums.ProviderType;
import com.hify.hify.modelprovider.dto.ModelProviderVO;
import com.hify.hify.modelprovider.service.LlmStreamService;
import com.hify.hify.modelprovider.service.ModelService;
import com.hify.hify.user.UserService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ConversationService T3 编排单测（不连真实库，Mock 所有外部依赖）。
 *
 * <p>覆盖 T3 验收点：SSE 事件顺序（meta→token→done）、知识召回 fallback（知识不可用不影响 LLM）、
 * LLM 失败 → error 事件 + assistant FAILED、消息与日志落库。
 */
@ExtendWith(MockitoExtension.class)
class ConversationServiceTest {

    @Mock
    private ConversationRepository conversationRepository;
    @Mock
    private MessageRepository messageRepository;
    @Mock
    private UserService userService;
    @Mock
    private ExecutorService sseExecutor;
    @Mock
    private AgentService agentService;
    @Mock
    private ModelService modelService;
    @Mock
    private LlmStreamService llmStreamService;
    @Mock
    private KbRetrievalService kbRetrievalService;
    @Mock
    private QueryIntentClassifier intentClassifier;
    @Mock
    private SkillService skillService;
    @Mock
    private ConversationLogAsyncWriter conversationLogAsyncWriter;
    @Mock
    private ToolRegistry toolRegistry;
    @Mock
    private ToolLoopRunner toolLoopRunner;

    private ConversationService svc;
    private ConversationService spySvc;
    private AgentVO agent;

    private final List<ConversationService.ChatEvent> events = new ArrayList<>();

    @BeforeEach
    void setUp() {
        // sseExecutor 同步执行，便于断言（仅 stream 测试用到，orchestrate 直测不调，故 lenient）
        lenient().when(sseExecutor.submit(any(Runnable.class))).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return mock(Future.class);
        });

        svc = new ConversationService(conversationRepository, messageRepository, userService,
                sseExecutor, agentService, modelService, llmStreamService, conversationLogAsyncWriter,
                toolRegistry, toolLoopRunner, skillService, kbRetrievalService, intentClassifier);
        spySvc = spy(svc);
        // M8/T3：工具循环默认无工具、直接回显 seedMessages（把编排循环与知识/流式断言解耦，
        // 让既有 SSE/知识注入用例不受函数调用改造影响）
        lenient().when(toolRegistry.resolve(any())).thenReturn(List.of());
        lenient().when(toolLoopRunner.run(any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> new ToolLoopRunner.LoopResult("", inv.getArgument(3)));
        // 拦截 send(...) 记录 SSE 事件，不真正写响应
        doAnswer(inv -> {
            events.add(inv.getArgument(1));
            return null;
        }).when(spySvc).send(any(SseEmitter.class), any(ConversationService.ChatEvent.class));

        // 公共 Mock：Agent / 厂商 / 召回 / 历史
        agent = mock(AgentVO.class);
        when(agent.modelProviderId()).thenReturn(10L);
        when(agent.knowledgeRefs()).thenReturn(List.of(100L));
        when(agent.systemPrompt()).thenReturn("你是助手");
        when(agent.name()).thenReturn("demo");
        when(agentService.getAgent(anyLong())).thenReturn(agent);

        ModelProviderVO provider = mock(ModelProviderVO.class);
        when(provider.providerType()).thenReturn(ProviderType.OPENAI);
        when(provider.model()).thenReturn("gpt-4");
        when(modelService.getProvider(anyLong())).thenReturn(provider);

        when(messageRepository.findByConversationIdOrderBySeqAsc(anyString())).thenReturn(List.of());
        // countByConversationId 仅 stream/终态统计路径用到，orchestrate 直测不调，标记 lenient
        lenient().when(messageRepository.countByConversationId(anyString())).thenReturn(2L);
        // 意图网关：非 QUESTION 跳过知识库检索；本测试 Agent 配了知识库，置 QUESTION 走检索分支
        when(intentClassifier.classify(anyString())).thenReturn(QueryIntentClassifier.Intent.QUESTION);
        when(messageRepository.save(any(Message.class))).thenAnswer(inv -> {
            Message m = inv.getArgument(0);
            if (m.getId() == null) {
                m.setId(99L);
            }
            return m;
        });
        when(conversationRepository.save(any(Conversation.class))).thenAnswer(inv -> inv.getArgument(0));
        doAnswer(inv -> null).when(conversationLogAsyncWriter).log(any(LogRecord.class));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private Conversation conv(Long id, String cid) {
        Conversation c = new Conversation();
        c.setId(id);
        c.setConversationId(cid);
        return c;
    }

    private Message assistantPending(Long id, String cid) {
        Message m = new Message();
        m.setId(id);
        m.setConversationId(cid);
        m.setUserId(1L);
        m.setRole(Message.MessageRole.ASSISTANT);
        m.setContent("");
        m.setSeq(2);
        m.setStatus(Message.MessageStatus.PENDING);
        return m;
    }

    @Test
    void testOrchestrate_normalFlow_emitsMetaTokenDoneAndPersistsAssistantAndLog() {
        when(kbRetrievalService.retrieve(anyLong(), anyString()))
                .thenReturn(List.of(new RetrievalResult("doc-x", 1, "知识内容A", 0.9, 1, RetrievalResult.RetrievalSource.SEMANTIC, 0.9)));
        when(llmStreamService.stream(any(), anyLong(), any()))
                .thenReturn(reactor.core.publisher.Flux.just("Hello", " world"));
        when(messageRepository.findById(99L)).thenReturn(Optional.of(assistantPending(99L, "conv-1")));
        when(conversationRepository.findById(1L)).thenReturn(Optional.of(conv(1L, "conv-1")));

        SseEmitter emitter = mock(SseEmitter.class);
        spySvc.orchestrate(emitter, new ChatRequest("conv-1", "hi", "1"), 1L, conv(1L, "conv-1"), 1L, 99L,
                new AtomicReference<>());

        // 事件顺序：retrieval-step(running) → retrieval-step(done) → meta → token(Hello) → token( world) → done
        // （prepare 阶段先发知识库检索进度 step，再发 meta；与 T3 工具循环 step 通道共用 sendStep）
        assertEquals(6, events.size());
        assertEquals("step", events.get(0).event());
        assertEquals("step", events.get(1).event());
        assertEquals("meta", events.get(2).event());
        assertEquals("token", events.get(3).event());
        assertEquals("Hello", events.get(3).content());
        assertEquals(" world", events.get(4).content());
        assertEquals("done", events.get(5).event());

        // assistant 消息落库：内容/状态/厂商/token（orchestrate 直测只终态落一次 assistant）
        ArgumentCaptor<Message> msgCap = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository, times(1)).save(msgCap.capture());
        Message assistant = msgCap.getAllValues().stream()
                .filter(m -> m.getRole() == Message.MessageRole.ASSISTANT).findFirst().orElseThrow();
        assertEquals("Hello world", assistant.getContent());
        assertEquals(Message.MessageStatus.SENT, assistant.getStatus());
        assertEquals("OPENAI", assistant.getProvider());
        assertTrue(assistant.getTokens() != null && assistant.getTokens() > 0);

        // 日志落库
        ArgumentCaptor<LogRecord> logCap = ArgumentCaptor.forClass(LogRecord.class);
        verify(conversationLogAsyncWriter).log(logCap.capture());
        LogRecord log = logCap.getValue();
        assertEquals(1L, log.getUserId());
        assertEquals("1", log.getAgentId());
        assertEquals("OPENAI", log.getProvider());
        assertEquals("gpt-4", log.getModel());
        assertEquals(false, log.getFallback());
        assertTrue(log.getInTok() > 0);
        assertTrue(log.getOutTok() > 0);
    }

    @Test
    void testOrchestrate_knowledgeRetrieveFails_returnsNoHitReplyWithoutCallingLlm() {
        // 配了 KB 但召回失败（异常）→ 按新设计短路返回固定话术，不再调用 LLM/工具循环编造
        when(kbRetrievalService.retrieve(anyLong(), anyString()))
                .thenThrow(new RuntimeException("kb down"));
        when(messageRepository.findById(99L)).thenReturn(Optional.of(assistantPending(99L, "conv-1")));
        when(conversationRepository.findById(1L)).thenReturn(Optional.of(conv(1L, "conv-1")));

        SseEmitter emitter = mock(SseEmitter.class);
        spySvc.orchestrate(emitter, new ChatRequest("conv-1", "hi", "1"), 1L, conv(1L, "conv-1"), 1L, 99L,
                new AtomicReference<>());

        // 召回确实被调用
        verify(kbRetrievalService).retrieve(anyLong(), anyString());
        // 短路：LLM 与工具循环均不应被调用
        verify(llmStreamService, never()).stream(any(), anyLong(), any());
        verify(toolLoopRunner, never()).run(any(), any(), any(), any(), any(), any());
        // 事件含短路返回的 token（固定话术含"没有查到"）
        boolean hit = events.stream().anyMatch(e -> "token".equals(e.event())
                && e.content() != null && e.content().contains("没有查到"));
        assertTrue(hit, "配KB但检索失败应短路返回固定话术，实际事件=" + events);
    }

    @Test
    void testOrchestrate_knowledgeRetrieveOk_injectsContextIntoSystemPrompt() {
        // 召回成功：system 提示应注入知识库内容
        when(kbRetrievalService.retrieve(anyLong(), anyString()))
                .thenReturn(List.of(new RetrievalResult("doc-1", 0, "MCP 是模型上下文协议", 0.9, 1, RetrievalResult.RetrievalSource.SEMANTIC, 0.9)));
        List<ChatMessage>[] captured = new List[1];
        when(llmStreamService.stream(any(), anyLong(), any())).thenAnswer(inv -> {
            captured[0] = inv.getArgument(0);
            return reactor.core.publisher.Flux.just("Hi");
        });
        when(messageRepository.findById(99L)).thenReturn(Optional.of(assistantPending(99L, "conv-1")));
        when(conversationRepository.findById(1L)).thenReturn(Optional.of(conv(1L, "conv-1")));

        SseEmitter emitter = mock(SseEmitter.class);
        spySvc.orchestrate(emitter, new ChatRequest("conv-1", "hi", "1"), 1L, conv(1L, "conv-1"), 1L, 99L,
                new AtomicReference<>());

        // 召回确实被调用过，且命中的是 Agent 配置的 kbId=100
        verify(kbRetrievalService).retrieve(anyLong(), anyString());

        // system 消息应拼接「参考知识库内容」——证明成功分支把知识喂给模型
        ChatMessage system = captured[0].get(0);
        assertTrue(system.getRole().equals("system"));
        assertTrue(system.getContent().contains("参考知识库内容"),
                "召回成功时应把知识塞进 system 提示，实际=" + system.getContent());
    }

    @Test
    void testOrchestrate_llmStreamFails_emitsErrorAndMarksAssistantFailed() {
        when(agent.knowledgeRefs()).thenReturn(List.of()); // 不配 KB，走 LLM 失败路径
        lenient().when(kbRetrievalService.retrieve(anyLong(), anyString())).thenReturn(List.of());
        when(llmStreamService.stream(any(), anyLong(), any()))
                .thenReturn(reactor.core.publisher.Flux.error(
                        new BizException(ErrorCode.LLM_CALL_FAILED, "boom")));
        Message pending = assistantPending(99L, "conv-1");
        when(messageRepository.findById(99L)).thenReturn(Optional.of(pending));
        when(conversationRepository.findById(1L)).thenReturn(Optional.of(conv(1L, "conv-1")));

        SseEmitter emitter = mock(SseEmitter.class);
        spySvc.orchestrate(emitter, new ChatRequest("conv-1", "hi", "1"), 1L, conv(1L, "conv-1"), 1L, 99L,
                new AtomicReference<>());

        // 事件：meta → error（不配 KB 不检索，无 retrieval-step）
        assertEquals(2, events.size());
        assertEquals("meta", events.get(0).event());
        assertEquals("error", events.get(1).event());

        // assistant 置 FAILED
        assertEquals(Message.MessageStatus.FAILED, pending.getStatus());

        // 日志 fallback=true
        ArgumentCaptor<LogRecord> logCap = ArgumentCaptor.forClass(LogRecord.class);
        verify(conversationLogAsyncWriter).log(logCap.capture());
        assertEquals(true, logCap.getValue().getFallback());
    }

    @Test
    void testStream_normalFlow_persistsUserAndAssistantMessages() {
        // 模拟登录用户
        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.getName()).thenReturn("alice");
        SecurityContext sc = mock(SecurityContext.class);
        when(sc.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(sc);
        when(userService.resolveUserId("alice")).thenReturn(1L);

        when(agent.knowledgeRefs()).thenReturn(List.of()); // 不配 KB，避免走"无命中短路"
        when(kbRetrievalService.retrieve(anyLong(), anyString())).thenReturn(List.of());
        when(llmStreamService.stream(any(), anyLong(), any()))
                .thenReturn(reactor.core.publisher.Flux.just("Hello", " world"));

        // 新建会话：resolveConversation 会 save 一个 Conversation
        Conversation savedConv = new Conversation();
        savedConv.setId(1L);
        savedConv.setConversationId("conv-new");
        when(conversationRepository.save(any(Conversation.class))).thenReturn(savedConv);
        when(messageRepository.findById(anyLong())).thenReturn(Optional.of(assistantPending(99L, "conv-new")));
        when(conversationRepository.findById(1L)).thenReturn(Optional.of(savedConv));

        // 不传 conversationId → 新建会话
        spySvc.stream(new ChatRequest(null, "hi", "1"));

        // user 消息 + assistant 消息均落库
        ArgumentCaptor<Message> msgCap = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository, times(3)).save(msgCap.capture());
        List<Message> saved = msgCap.getAllValues();
        assertTrue(saved.stream().anyMatch(m -> m.getRole() == Message.MessageRole.USER && "hi".equals(m.getContent())));
        assertTrue(saved.stream().anyMatch(m -> m.getRole() == Message.MessageRole.ASSISTANT
                && Message.MessageStatus.SENT == m.getStatus() && "Hello world".equals(m.getContent())));
    }
}
