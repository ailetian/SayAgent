package com.hify.hify.conversation;

import com.hify.hify.conversation.dto.LogRecord;
import com.hify.hify.conversation.entity.ConversationLog;
import com.hify.hify.conversation.repository.ConversationLogRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M6 T6（单测）：SSE 流出完整内容 + 日志异步落库 —— 其二：日志异步落库。
 *
 * <p>大白话：{@link ConversationLogAsyncWriter#log(LogRecord)} 把任务丢到 {@code logExecutor} 后台线程后立刻返回，
 * 真正的 {@code conversation_log} 落库在后台完成。本测试用「真实单线程池」做 logExecutor，
 * 以 Mockito 的 {@code verify(..., timeout(...))} 断言落库"异步可见"，并校验 LogRecord → ConversationLog 字段映射、
 * fallback 标记、以及仓储抛错时绝不冒泡给调用方（§7.3 / T4 禁止项）。
 */
@ExtendWith(MockitoExtension.class)
class ConversationLogAsyncWriterTest {

    @Mock
    private ConversationLogRepository conversationLogRepository;

    /** 真实单线程池：证明 log() 提交后立刻返回、落库在后台线程完成（"异步"）。 */
    private ExecutorService logExecutor;
    private ConversationLogAsyncWriter writer;

    @BeforeEach
    void setUp() {
        logExecutor = Executors.newSingleThreadExecutor();
        writer = new ConversationLogAsyncWriter(conversationLogRepository, logExecutor);
    }

    @AfterEach
    void tearDown() {
        if (logExecutor != null) {
            logExecutor.shutdownNow();
        }
    }

    private LogRecord sampleRecord(boolean fallback) {
        LogRecord r = new LogRecord();
        r.setUserId(1L);
        r.setAgentId("1");
        r.setConversationId("conv-xyz");
        r.setQuestion("你好");
        r.setInTok(12);
        r.setOutTok(8);
        r.setProvider("OPENAI");
        r.setModel("gpt-4");
        r.setFallback(fallback);
        return r;
    }

    /**
     * 验收点2：log() 异步落库，且 LogRecord 字段完整映射到 ConversationLog。
     */
    @Test
    void testLog_asyncWriter_persistsMappedConversationLog() {
        LogRecord rec = sampleRecord(false);
        writer.log(rec);

        // 异步可见：等待后台线程完成 save（timeout 即"异步"的标准断言方式）
        ArgumentCaptor<ConversationLog> cap = ArgumentCaptor.forClass(ConversationLog.class);
        verify(conversationLogRepository, timeout(5000)).save(cap.capture());

        ConversationLog entry = cap.getValue();
        assertEquals(1L, entry.getUserId());
        assertEquals("1", entry.getAgentId());
        assertEquals("conv-xyz", entry.getConversationId());
        assertEquals("你好", entry.getQuestion());
        assertEquals(12, entry.getInTok());
        assertEquals(8, entry.getOutTok());
        assertEquals("OPENAI", entry.getProvider());
        assertEquals("gpt-4", entry.getModel());
        assertFalse(entry.getFallback());
    }

    /**
     * 验收点2（降级分支）：fallback=true 时映射进 ConversationLog.fallback。
     */
    @Test
    void testLog_fallbackTrue_recordMapsFallbackField() {
        LogRecord rec = sampleRecord(true);
        writer.log(rec);

        ArgumentCaptor<ConversationLog> cap = ArgumentCaptor.forClass(ConversationLog.class);
        verify(conversationLogRepository, timeout(5000)).save(cap.capture());
        ConversationLog entry = cap.getValue();
        assertTrue(entry.getFallback());
        assertEquals("OPENAI", entry.getProvider());
        assertEquals("gpt-4", entry.getModel());
    }

    /**
     * 验收点（健壮性）：仓储写库抛错时，异步门面只记 WARN、绝不冒泡给调用方（§7.3 / T4 禁止项）。
     */
    @Test
    void testLog_repositoryThrows_doesNotPropagateToCaller() {
        when(conversationLogRepository.save(any(ConversationLog.class)))
                .thenThrow(new RuntimeException("db down"));
        LogRecord rec = sampleRecord(false);

        // 不应抛异常冒泡到调用方（异步持久化为尽力而为）
        writer.log(rec);

        // 后台线程确实尝试了 save（即便最终失败）
        verify(conversationLogRepository, timeout(5000)).save(any(ConversationLog.class));
    }
}
