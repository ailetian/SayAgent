package com.hify.hify.conversation;

import com.hify.hify.conversation.dto.LogRecord;
import com.hify.hify.conversation.entity.ConversationLog;
import com.hify.hify.conversation.repository.ConversationLogRepository;

import java.util.concurrent.ExecutorService;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 对话日志异步落库门面（M6 T4）。
 *
 * <p>大白话：调用方（T3 ConversationService）在对话结束（成功/失败）时构造一个 {@link LogRecord} 调
 * {@link #log(LogRecord)}，本方法把任务提交到专用 {@code logExecutor} 后立刻返回，不阻塞 SSE 推送主链路
 * （§4.6 / §8 异步解耦）。落库动作在后台线程完成；单条写失败只记日志、不抛异常、不影响对话响应。
 *
 * <p>与 T2 的 {@code sseExecutor} 隔离（§4.2 线程纪律：慢活互相隔离防头阻塞），使用 T4 自建的
 * {@code logExecutor}，不复用 Tomcat / SSE 主线程。
 */
@Slf4j
@Component
public class ConversationLogAsyncWriter {

    private final ConversationLogRepository conversationLogRepository;
    private final ExecutorService logExecutor;

    public ConversationLogAsyncWriter(ConversationLogRepository conversationLogRepository,
                                      @Qualifier("logExecutor") ExecutorService logExecutor) {
        this.conversationLogRepository = conversationLogRepository;
        this.logExecutor = logExecutor;
    }

    /**
     * 异步写一条对话流水日志：把任务提交到后台线程后方法立即返回（§8 异步落库）。
     *
     * @param record 非敏感流水载体（见 {@link LogRecord}），不含任何密钥（§7.11 规则37）
     */
    public void log(LogRecord record) {
        logExecutor.submit(() -> persist(record));
    }

    /** 后台线程执行：映射 LogRecord → ConversationLog 并落库；单条失败仅记日志不抛。 */
    private void persist(LogRecord r) {
        try {
            ConversationLog entry = new ConversationLog();
            entry.setUserId(r.getUserId());
            entry.setAgentId(r.getAgentId());
            entry.setConversationId(r.getConversationId());
            entry.setQuestion(r.getQuestion());
            entry.setInTok(r.getInTok());
            entry.setOutTok(r.getOutTok());
            entry.setProvider(r.getProvider());
            entry.setModel(r.getModel());
            entry.setFallback(r.getFallback());
            conversationLogRepository.save(entry);
        } catch (Exception e) {
            // 异步落库为尽力而为，单条失败仅记 WARN 不抛异常、不冒泡，避免中断对话主流程（§7.3 / T4 禁止项）
            log.warn("write conversation_log failed (best-effort, skipped) userId={} convId={}",
                    r.getUserId(), r.getConversationId(), e);
        }
    }
}
