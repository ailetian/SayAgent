package com.hify.hify.knowledge.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * K0808 T2 单测：意图分类器 5 类 + 边界 + 不误杀。
 * 纯函数、零 Spring 上下文：直接 new QueryRewriter + QueryIntentClassifier。
 */
class QueryIntentClassifierTest {

    private final QueryIntentClassifier classifier =
            new QueryIntentClassifier(new QueryRewriter());

    @Test
    void testGreeting_hi() {
        assertEquals(QueryIntentClassifier.Intent.GREETING, classifier.classify("你好"));
    }

    @Test
    void testGreeting_thanks() {
        assertEquals(QueryIntentClassifier.Intent.GREETING, classifier.classify("谢谢"));
    }

    @Test
    void testGreeting_youThere() {
        assertEquals(QueryIntentClassifier.Intent.GREETING, classifier.classify("在吗"));
    }

    @Test
    void testIdentity_whoAreYou() {
        assertEquals(QueryIntentClassifier.Intent.IDENTITY, classifier.classify("你是谁"));
    }

    @Test
    void testIdentity_whatCanYouDo() {
        assertEquals(QueryIntentClassifier.Intent.IDENTITY, classifier.classify("你能做什么"));
    }

    @Test
    void testMeaningless_questionMarks() {
        assertEquals(QueryIntentClassifier.Intent.MEANINGLESS, classifier.classify("？？？"));
    }

    @Test
    void testMeaningless_um() {
        assertEquals(QueryIntentClassifier.Intent.MEANINGLESS, classifier.classify("嗯"));
    }

    @Test
    void testMeaningless_haha() {
        assertEquals(QueryIntentClassifier.Intent.MEANINGLESS, classifier.classify("哈哈"));
    }

    @Test
    void testQuestion_leavePolicy() {
        assertEquals(QueryIntentClassifier.Intent.QUESTION, classifier.classify("年假怎么请"));
    }

    @Test
    void testQuestion_greetingPrefixNotKilled() {
        // 关键：清洗后剩「年假怎么请」，不得误杀为 GREETING/MEANINGLESS
        assertEquals(QueryIntentClassifier.Intent.QUESTION, classifier.classify("你好，年假怎么请"));
    }

    @Test
    void testTool_actionWords_placeholder() {
        // TOOL 本期仅占位识别，不实现
        assertEquals(QueryIntentClassifier.Intent.TOOL, classifier.classify("帮我发邮件给张三"));
    }

    @Test
    void testBoundary_nullAndBlank() {
        assertEquals(QueryIntentClassifier.Intent.MEANINGLESS, classifier.classify(null));
        assertEquals(QueryIntentClassifier.Intent.MEANINGLESS, classifier.classify("   "));
        assertEquals(QueryIntentClassifier.Intent.MEANINGLESS, classifier.classify(""));
    }

    @Test
    void testOverload_withHistoryNull_unchanged() {
        assertEquals(QueryIntentClassifier.Intent.QUESTION,
                classifier.classify("你好，年假怎么请", null));
    }

    @Test
    void testIntentEnum_hasFiveValues() {
        QueryIntentClassifier.Intent[] values = QueryIntentClassifier.Intent.values();
        assertEquals(5, values.length, "Intent 枚举必须含 5 个值");
        assertNotNull(QueryIntentClassifier.Intent.GREETING);
        assertNotNull(QueryIntentClassifier.Intent.IDENTITY);
        assertNotNull(QueryIntentClassifier.Intent.MEANINGLESS);
        assertNotNull(QueryIntentClassifier.Intent.TOOL);
        assertNotNull(QueryIntentClassifier.Intent.QUESTION);
    }
}
