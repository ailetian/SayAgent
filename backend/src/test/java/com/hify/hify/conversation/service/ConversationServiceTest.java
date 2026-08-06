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
import com.hify.hify.knowledge.entity.Document;
import com.hify.hify.knowledge.repository.DocumentRepository;
import com.hify.hify.knowledge.retriever.RetrievalPort;
import com.hify.hify.mcp.McpService;
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
    private RetrievalPort retrievalPort;
    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private ConversationLogAsyncWriter conversationLogAsyncWriter;
    @Mock
    private McpService mcpService;

    private ConversationService svc;
    private ConversationService spySvc;

    private final List<ConversationService.ChatEvent> events = new ArrayList<>();

    @BeforeEach
    void setUp() {
        // sseExecutor 同步执行，便于断言（仅 stream 测试用到，orchestrate 直测不调，故 lenient）
        lenient().when(sseExecutor.submit(any(Runnable.class))).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return mock(Future.class);
        });

        svc = new ConversationService(conversationRepository, messageRepository, userService,
                sseExecutor, agentService, modelService, llmStreamService, retrievalPort,
                documentRepository, conversationLogAsyncWriter, mcpService);
        spySvc = spy(svc);
        // 拦截 send(...) 记录 SSE 事件，不真正写响应
        doAnswer(inv -> {
            events.add(inv.getArgument(1));
            return null;
        }).when(spySvc).send(any(SseEmitter.class), any(ConversationService.ChatEvent.class));

        // 公共 Mock：Agent / 厂商 / 召回 / 历史
        AgentVO agent = mock(AgentVO.class);
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
        // K11：retrieveKnowledge 先取本库未软删文档 id 下推 PG；默认返回一篇保证 retrieve 真被调用
        Document kbDoc = new Document();
        kbDoc.setDocumentId("doc-x");
        when(documentRepository.findByKbId(anyLong())).thenReturn(List.of(kbDoc));
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
        when(retrievalPort.retrieve(anyString(), anyList(), anyInt(), anyDouble()))
                .thenReturn(List.of(new RetrievalPort.RetrievedChunk("doc-x", 1, "知识内容A", 0.9)));
        when(llmStreamService.stream(any(), anyLong(), any()))
                .thenReturn(reactor.core.publisher.Flux.just("Hello", " world"));
        when(messageRepository.findById(99L)).thenReturn(Optional.of(assistantPending(99L, "conv-1")));
        when(conversationRepository.findById(1L)).thenReturn(Optional.of(conv(1L, "conv-1")));

        SseEmitter emitter = mock(SseEmitter.class);
        spySvc.orchestrate(emitter, new ChatRequest("conv-1", "hi", "1"), 1L, conv(1L, "conv-1"), 1L, 99L,
                new AtomicReference<>());

        // 事件顺序：meta → token(Hello) → token( world) → done
        assertEquals(4, events.size());
        assertEquals("meta", events.get(0).event());
        assertEquals("token", events.get(1).event());
        assertEquals("Hello", events.get(1).content());
        assertEquals(" world", events.get(2).content());
        assertEquals("done", events.get(3).event());

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
    void testOrchestrate_knowledgeRetrieveFails_streamsWithoutContext() {
        // 召回炸了：应 fallback（空知识），但不阻断 LLM 流式
        when(retrievalPort.retrieve(anyString(), anyList(), anyInt(), anyDouble()))
                .thenThrow(new RuntimeException("kb down"));
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

        // 事件仍是 meta → token → done，没有 error
        assertEquals("meta", events.get(0).event());
        assertEquals("token", events.get(1).event());
        assertEquals("done", events.get(2).event());

        // system 消息不应包含「参考知识库」——证明 fallback 后未拼知识
        ChatMessage system = captured[0].get(0);
        assertTrue(system.getRole().equals("system"));
        assertTrue(!system.getContent().contains("参考知识库"),
                "召回失败时不应把知识塞进 system 提示，实际=" + system.getContent());
        // 召回确实被调用过
        verify(retrievalPort).retrieve(anyString(), anyList(), anyInt(), anyDouble());
    }

    @Test
    void testOrchestrate_knowledgeRetrieveOk_injectsContextIntoSystemPrompt() {
        // 召回成功：system 提示应注入知识库内容
        when(retrievalPort.retrieve(anyString(), anyList(), anyInt(), anyDouble()))
                .thenReturn(List.of(new RetrievalPort.RetrievedChunk("doc-1", 0, "MCP 是模型上下文协议", 0.9)));
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
        verify(retrievalPort).retrieve(anyString(), anyList(), anyInt(), anyDouble());

        // system 消息应拼接「参考知识库内容」——证明成功分支把知识喂给模型
        ChatMessage system = captured[0].get(0);
        assertTrue(system.getRole().equals("system"));
        assertTrue(system.getContent().contains("参考知识库内容"),
                "召回成功时应把知识塞进 system 提示，实际=" + system.getContent());
    }

    @Test
    void testOrchestrate_llmStreamFails_emitsErrorAndMarksAssistantFailed() {
        when(retrievalPort.retrieve(anyString(), anyList(), anyInt(), anyDouble())).thenReturn(List.of());
        when(llmStreamService.stream(any(), anyLong(), any()))
                .thenReturn(reactor.core.publisher.Flux.error(
                        new BizException(ErrorCode.LLM_CALL_FAILED, "boom")));
        Message pending = assistantPending(99L, "conv-1");
        when(messageRepository.findById(99L)).thenReturn(Optional.of(pending));
        when(conversationRepository.findById(1L)).thenReturn(Optional.of(conv(1L, "conv-1")));

        SseEmitter emitter = mock(SseEmitter.class);
        spySvc.orchestrate(emitter, new ChatRequest("conv-1", "hi", "1"), 1L, conv(1L, "conv-1"), 1L, 99L,
                new AtomicReference<>());

        // 事件：meta → error（无 done）
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

        when(retrievalPort.retrieve(anyString(), anyList(), anyInt(), anyDouble())).thenReturn(List.of());
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
