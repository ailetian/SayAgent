package com.hify.hify.conversation.service;

import com.hify.hify.agent.dto.AgentVO;
import com.hify.hify.agent.service.AgentService;
import com.hify.hify.conversation.ConversationLogAsyncWriter;
import com.hify.hify.conversation.dto.ChatRequest;
import com.hify.hify.conversation.dto.LogRecord;
import com.hify.hify.conversation.entity.Conversation;
import com.hify.hify.conversation.entity.Message;
import com.hify.hify.conversation.repository.ConversationRepository;
import com.hify.hify.conversation.repository.MessageRepository;
import com.hify.hify.knowledge.retriever.RetrievalPort;
import com.hify.hify.mcp.McpService;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M6 T6（单测）：SSE 流出完整内容 + 日志异步落库 —— 其一：流式完整性。
 *
 * <p>大白话：把 LLM 换成固定分片 Flux（Mock），从 SSE 事件流里把所有 token 帧内容拼起来，
 * 必须等于 LLM 的完整输出；并在流式结束时调用了 {@code emitter.complete()}。另附 §4.6 断连取消的接线断言。
 * 沿用 {@code ConversationServiceTest} 的隔离套路：sseExecutor 同步执行 + spy(send) 拦截 ChatEvent，不碰真实库。
 * 注意：{@code stream()} 内部自行 {@code new SseEmitter()}，需用 {@code mockConstruction} 捕获该实例才能断言其交互。
 */
@ExtendWith(MockitoExtension.class)
class ConversationServiceStreamTest {

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
    private ConversationLogAsyncWriter conversationLogAsyncWriter;
    @Mock
    private McpService mcpService;

    /** sseExecutor.submit 返回的 Future 句柄，用于断连取消断言。 */
    private final Future<?> futureMock = mock(Future.class);
    private final List<ConversationService.ChatEvent> events = new ArrayList<>();

    private ConversationService svc;
    private ConversationService spySvc;

    @BeforeEach
    void setUp() {
        // sseExecutor 同步执行，便于在同一线程内断言完整 SSE 事件序列
        lenient().when(sseExecutor.submit(any(Runnable.class))).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return futureMock;
        });

        svc = new ConversationService(conversationRepository, messageRepository, userService,
                sseExecutor, agentService, modelService, llmStreamService, retrievalPort,
                conversationLogAsyncWriter, mcpService);
        spySvc = spy(svc);
        // 拦截 send(...) 记录 SSE 事件，不真正写响应
        doAnswer(inv -> {
            events.add(inv.getArgument(1));
            return null;
        }).when(spySvc).send(any(SseEmitter.class), any(ConversationService.ChatEvent.class));

        // 公共 Mock：Agent / 厂商 / 召回（空）/ 历史
        AgentVO agent = mock(AgentVO.class);
        when(agent.modelProviderId()).thenReturn(10L);
        when(agent.knowledgeRefs()).thenReturn(List.of());
        when(agent.systemPrompt()).thenReturn("你是助手");
        when(agent.name()).thenReturn("demo");
        when(agentService.getAgent(anyLong())).thenReturn(agent);

        ModelProviderVO provider = mock(ModelProviderVO.class);
        when(provider.providerType()).thenReturn(ProviderType.OPENAI);
        when(provider.model()).thenReturn("gpt-4");
        when(modelService.getProvider(anyLong())).thenReturn(provider);

        when(messageRepository.findByConversationIdOrderBySeqAsc(anyString())).thenReturn(List.of());
        when(messageRepository.countByConversationId(anyString())).thenReturn(0L);
        when(messageRepository.save(any(Message.class))).thenAnswer(inv -> {
            Message m = inv.getArgument(0);
            if (m.getId() == null) {
                m.setId(99L);
            }
            return m;
        });
        when(messageRepository.findById(anyLong())).thenReturn(Optional.of(assistantPending(99L, "conv-stream")));

        when(conversationRepository.save(any(Conversation.class))).thenAnswer(inv -> {
            Conversation c = inv.getArgument(0);
            if (c.getId() == null) {
                c.setId(1L);
            }
            return c;
        });
        when(conversationRepository.findById(anyLong())).thenReturn(Optional.of(conv(1L, "conv-stream")));

        // 日志异步门面：log 是尽力而为，这里直接 no-op
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

    private void loginAs(String username, Long userId) {
        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.getName()).thenReturn(username);
        SecurityContext sc = mock(SecurityContext.class);
        when(sc.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(sc);
        when(userService.resolveUserId(username)).thenReturn(userId);
    }

    /**
     * 验收点1：SSE 流出的全部 token 拼接 == LLM 完整输出，且结束时 complete()。
     */
    @Test
    void testStream_mockModelReturnsTokenFlux_concatEqualsFullAnswerAndCompletes() {
        loginAs("alice", 1L);
        // 预设 LLM 分片：拼接必须还原完整答案
        String[] tokens = {"今天", "天气", "不错", "，", "适合", "写代码"};
        String fullAnswer = String.join("", tokens);
        when(llmStreamService.stream(any(), anyLong(), any()))
                .thenReturn(reactor.core.publisher.Flux.just(tokens));

        SseEmitter[] emitted = new SseEmitter[1];
        try (var ignored = mockConstruction(SseEmitter.class, (mock, ctx) -> emitted[0] = mock)) {
            spySvc.stream(new ChatRequest(null, "hi", "1"));

            // (1) 收集所有 token 帧内容并拼接
            StringBuilder sb = new StringBuilder();
            List<String> tokenContents = new ArrayList<>();
            for (ConversationService.ChatEvent e : events) {
                if ("token".equals(e.event()) && e.content() != null) {
                    sb.append(e.content());
                    tokenContents.add(e.content());
                }
            }
            assertEquals(fullAnswer, sb.toString(),
                    "SSE 流出的全部 token 拼接必须等于 LLM 完整输出，实际=" + sb);
            assertEquals(tokens.length, tokenContents.size(), "token 帧数量应等于 LLM 分片数");

            // (2) 事件序列合法：首帧 meta、末帧 done
            assertEquals("meta", events.get(0).event());
            assertEquals("done", events.get(events.size() - 1).event());

            // (3) 流式结束调用了 emitter.complete()
            verify(emitted[0]).complete();
        }

        // (4) 日志被异步提交，且携带正确归属与输出 token 用量
        ArgumentCaptor<LogRecord> logCap = ArgumentCaptor.forClass(LogRecord.class);
        verify(conversationLogAsyncWriter).log(logCap.capture());
        LogRecord log = logCap.getValue();
        assertEquals(1L, log.getUserId());
        assertTrue(log.getOutTok() > 0, "落库日志应记录输出 token 用量（非降级分支）");
    }

    /**
     * 建议验收点：§4.6 断连取消接线 —— stream 必须注册 onTimeout/onError 回调，
     * 模拟客户端断连（onError 回调）应触发编排线程取消（future.cancel(true)）。
     */
    @Test
    void testStream_disconnectViaOnError_cancelsOrchestrationFuture() {
        loginAs("alice", 1L);
        when(llmStreamService.stream(any(), anyLong(), any()))
                .thenReturn(reactor.core.publisher.Flux.just("a", "b"));

        SseEmitter[] emitted = new SseEmitter[1];
        Consumer<Throwable>[] errCb = new Consumer[1];
        try (var ignored = mockConstruction(SseEmitter.class, (mock, ctx) -> {
            emitted[0] = mock;
            // 捕获 onError 回调，便于稍后模拟客户端断连
            doAnswer(inv -> {
                errCb[0] = inv.getArgument(0);
                return null;
            }).when(mock).onError(any());
        })) {
            spySvc.stream(new ChatRequest(null, "hi", "1"));

            // §4.6：必须注册断连/超时回调
            verify(emitted[0]).onError(any());
            verify(emitted[0]).onTimeout(any());

            // 模拟客户端断连 → 回调触发 cancelStream → 编排 Future 被取消
            errCb[0].accept(new IOException("client disconnected"));
            verify(futureMock).cancel(true);
        }
    }
}
