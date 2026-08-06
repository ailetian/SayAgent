package com.hify.hify.knowledge.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 评测指标纯函数单测（K10）。不连真库、不调 LLM，只锁死公式（§7.10 规则35）。
 */
class EvalMetricsTest {

    @Test
    void recallAtK_normalizesByTotalRelevant() {
        assertEquals(0.5, EvalMetrics.recallAtK(2, 4), 1e-9);
        assertEquals(1.0, EvalMetrics.recallAtK(3, 3), 1e-9);
        assertEquals(0.0, EvalMetrics.recallAtK(0, 0), 1e-9);
        assertEquals(0.0, EvalMetrics.recallAtK(5, 0), 1e-9);
    }

    @Test
    void mrr_isReciprocalOfFirstRelevantRank() {
        assertEquals(1.0, EvalMetrics.mrr(1), 1e-9);
        assertEquals(0.5, EvalMetrics.mrr(2), 1e-9);
        assertEquals(0.0, EvalMetrics.mrr(0), 1e-9);
    }

    @Test
    void ndcgAtK_idealRankingIsOne() {
        assertEquals(1.0, EvalMetrics.ndcgAtK(List.of(1, 1, 1), 3), 1e-9);
    }

    @Test
    void ndcgAtK_partialRelevanceMatchesHandComputation() {
        // DCG = 1/log2(2) + 0 + 1/log2(4) = 1 + 0.5 = 1.5
        // IDCG = 1/log2(2) + 1/log2(3) = 1 + 0.6309 = 1.6309
        double expected = 1.5 / (1.0 + 1.0 / (Math.log(3) / Math.log(2)));
        assertEquals(expected, EvalMetrics.ndcgAtK(List.of(1, 0, 1), 3), 1e-6);
    }

    @Test
    void ndcgAtK_noRelevantReturnsZero() {
        assertEquals(0.0, EvalMetrics.ndcgAtK(List.of(0, 0, 0), 3), 1e-9);
        assertEquals(0.0, EvalMetrics.ndcgAtK(List.of(), 3), 1e-9);
    }

    @Test
    void contextPrecisionAndRecall() {
        assertEquals(0.6, EvalMetrics.contextPrecision(3, 5), 1e-9);
        assertEquals(1.0, EvalMetrics.contextPrecision(5, 5), 1e-9);
        assertEquals(0.0, EvalMetrics.contextPrecision(0, 0), 1e-9);
        assertEquals(0.5, EvalMetrics.contextRecall(2, 4), 1e-9);
        assertEquals(1.0, EvalMetrics.contextRecall(4, 4), 1e-9);
        assertEquals(0.0, EvalMetrics.contextRecall(0, 0), 1e-9);
    }

    @Test
    void mean_ignoresEmptyAndNan() {
        assertEquals(0.85, EvalMetrics.mean(List.of(0.9, 0.8)), 1e-9);
        assertEquals(0.0, EvalMetrics.mean(List.of()), 1e-9);
        assertEquals(0.0, EvalMetrics.mean(List.of(Double.NaN)), 1e-9);
    }

    @Test
    void p95_nearestRank() {
        List<Long> ten = List.of(10L, 20L, 30L, 40L, 50L, 60L, 70L, 80L, 90L, 100L);
        assertEquals(100L, EvalMetrics.p95(ten));
        assertEquals(42L, EvalMetrics.p95(List.of(42L)));
        assertEquals(0L, EvalMetrics.p95(List.of()));
    }

    @Test
    void gate_passesWhenAllThresholdsMet() {
        assertTrue(EvalMetrics.gate(0.95, 0.90, 0.0, 0.10, 2000L));
    }

    @Test
    void gate_failsOnEachDimension() {
        assertFalse(EvalMetrics.gate(0.80, 0.90, 0.0, 0.10, 2000L));   // recall low
        assertFalse(EvalMetrics.gate(0.95, 0.80, 0.0, 0.10, 2000L));   // faithfulness low
        assertFalse(EvalMetrics.gate(0.95, 0.90, 0.10, 0.10, 2000L));  // wrong refusal high
        assertFalse(EvalMetrics.gate(0.95, 0.90, 0.0, 0.20, 2000L));  // hallucination high
        assertFalse(EvalMetrics.gate(0.95, 0.90, 0.0, 0.10, 5000L));   // p95 high
    }

    private static void assertTrue(boolean b) {
        org.junit.jupiter.api.Assertions.assertTrue(b);
    }

    private static void assertFalse(boolean b) {
        org.junit.jupiter.api.Assertions.assertFalse(b);
    }
}
