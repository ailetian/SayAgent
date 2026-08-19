package com.sayagent.modelprovider.client;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 流式输出的 token 用量收集器（M6 T3）。
 *
 * <p>大白话：LLM 流式接口一边吐字一边在最后一个 chunk 里带 usage，
 * 这个容器在编排结束后拿回最终的输入/输出 token（供落库与 §4.9 统计）。
 * 用 AtomicInteger 以便在 Reactor 订阅线程回写时线程安全。
 */
public class TokenUsage {

    private final AtomicInteger promptTokens = new AtomicInteger(0);
    private final AtomicInteger completionTokens = new AtomicInteger(0);

    /** 末片回填用量（仅当 >0 才覆盖，避免 0 把真实值冲掉）。 */
    public void setUsage(int prompt, int completion) {
        if (prompt > 0) {
            promptTokens.set(prompt);
        }
        if (completion > 0) {
            completionTokens.set(completion);
        }
    }

    public int getPromptTokens() {
        return promptTokens.get();
    }

    public int getCompletionTokens() {
        return completionTokens.get();
    }
}
