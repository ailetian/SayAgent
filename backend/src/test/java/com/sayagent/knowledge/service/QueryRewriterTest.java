package com.sayagent.knowledge.service;

import com.sayagent.modelprovider.client.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * K5 QueryRewriter 单测（R4 指代消解，规则版确定性）。不调 LLM。
 */
class QueryRewriterTest {

    private final QueryRewriter rewriter = new QueryRewriter();

    @Test
    void demonstrativeAnaphora_extractsExplicitNoun() {
        // 「那婚假呢？」→ 含「婚假」完整查询（验收点）
        List<ChatMessage> history = List.of(new ChatMessage("user", "年假怎么请"));
        String rewritten = rewriter.rewrite("那婚假呢？", history);
        assertTrue(rewritten.contains("婚假"), "改写应含婚假，实际=" + rewritten);
    }

    @Test
    void bareAnaphora_resolvesFromHistory() {
        // 「它呢」→ 从历史最近用户问题抠出核心名词「年假」
        List<ChatMessage> history = List.of(new ChatMessage("user", "年假怎么请"));
        String rewritten = rewriter.rewrite("它呢", history);
        assertEquals("年假", rewritten);
    }

    @Test
    void leadingTrailingFiller_stripped_keepsConstraint() {
        // 口语清理但保留原约束（只改表达，E8）
        String rewritten = rewriter.rewrite("你好，请问年假怎么请？", List.of());
        assertEquals("年假怎么请", rewritten);
    }

    @Test
    void plainQuery_unchanged() {
        String rewritten = rewriter.rewrite("年假相关规定", List.of());
        assertEquals("年假相关规定", rewritten);
    }
}
