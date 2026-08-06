package com.hify.hify.knowledge.eval;

import java.util.Collections;
import java.util.List;

/**
 * 评测指标纯函数（K10）。
 *
 * <p>大白话：把「检索命中的相关性」「答案有没有编造」「问题答没答到」翻译成 0~1 的数字，
 * 不依赖任何 Spring / 数据库 / 大模型——纯数学，方便单测锁死公式（不靠连真库）。
 *
 * <p>所有方法对「分母为 0 / 空输入」都返回 0.0，绝不抛异常（评测脚本绝不能因为某题没命中就崩）。
 */
public final class EvalMetrics {

    /** 门禁阈值（需求 §9.2 / §9.4）：未达基线不许上线。 */
    public static final double GATE_RECALL_AT5 = 0.90;
    public static final double GATE_FAITHFULNESS = 0.85;
    public static final double GATE_WRONG_REFUSAL_RATE = 0.05;
    public static final double GATE_HALLUCINATION_RATE = 0.10;
    public static final long GATE_P95_LATENCY_MS = 3000L;

    private EvalMetrics() {
    }

    /**
     * Recall@K：命中相关文档数 / 全部相关文档数（相关文档来自本次检索返回集合）。
     *
     * @param relevantInK    top-K 里命中的相关文档数
     * @param totalRelevant  本次检索返回集合里的总相关文档数
     */
    public static double recallAtK(int relevantInK, int totalRelevant) {
        if (totalRelevant <= 0) {
            return 0.0;
        }
        return clamp01((double) relevantInK / totalRelevant);
    }

    /**
     * MRR：第一个相关文档排名的倒数（1/rank）。无相关文档返回 0.0。
     *
     * @param firstRelevantRank 第一个相关文档的融合名次（1 起；≤0 表示无）
     */
    public static double mrr(int firstRelevantRank) {
        if (firstRelevantRank <= 0) {
            return 0.0;
        }
        return 1.0 / firstRelevantRank;
    }

    /**
     * NDCG@K：折损累计增益归一化（二元相关性，命中=1 未命中=0）。
     *
     * @param binaryRelevance 按融合名次排序的相关性列表（1=相关，0=不相关）
     * @param k               取前 k 位
     */
    public static double ndcgAtK(List<Integer> binaryRelevance, int k) {
        if (binaryRelevance == null || binaryRelevance.isEmpty() || k <= 0) {
            return 0.0;
        }
        int n = Math.min(k, binaryRelevance.size());
        double dcg = 0.0;
        for (int i = 0; i < n; i++) {
            int rel = binaryRelevance.get(i) == null ? 0 : binaryRelevance.get(i);
            dcg += (Math.pow(2, rel) - 1) / log2(i + 2);
        }
        // 理想排序：前 min(k, 相关总数) 位全 1
        long totalRelevant = binaryRelevance.stream().filter(r -> r != null && r == 1).count();
        int idealN = (int) Math.min(k, totalRelevant);
        double idcg = 0.0;
        for (int i = 0; i < idealN; i++) {
            idcg += (Math.pow(2, 1) - 1) / log2(i + 2);
        }
        if (idcg <= 0.0) {
            return 0.0;
        }
        return clamp01(dcg / idcg);
    }

    /**
     * Context Precision@K：top-K 命中相关块数 / K。
     *
     * @param relevantInK top-K 命中的相关块数
     * @param k           K
     */
    public static double contextPrecision(int relevantInK, int k) {
        if (k <= 0) {
            return 0.0;
        }
        return clamp01((double) relevantInK / k);
    }

    /**
     * Context Recall：被检索块覆盖的关键词数 / 题集关键词总数。
     *
     * @param keywordsCovered 至少命中一个检索块的关键词数
     * @param totalKeywords   题集关键词总数
     */
    public static double contextRecall(int keywordsCovered, int totalKeywords) {
        if (totalKeywords <= 0) {
            return 0.0;
        }
        return clamp01((double) keywordsCovered / totalKeywords);
    }

    /**
     * 平均值（自动忽略 NaN/空）。
     */
    public static double mean(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        int n = 0;
        for (Double v : values) {
            if (v != null && !Double.isNaN(v)) {
                sum += v;
                n++;
            }
        }
        return n == 0 ? 0.0 : sum / n;
    }

    /**
     * P95 延迟（毫秒，最近秩法）：升序后取 ceil(0.95·n)-1 位。
     */
    public static long p95(List<Long> latenciesMs) {
        if (latenciesMs == null || latenciesMs.isEmpty()) {
            return 0L;
        }
        List<Long> sorted = new java.util.ArrayList<>(latenciesMs);
        Collections.sort(sorted);
        int idx = (int) Math.ceil(0.95 * sorted.size()) - 1;
        if (idx < 0) {
            idx = 0;
        }
        return sorted.get(Math.min(idx, sorted.size() - 1));
    }

    /**
     * 门禁判定：Recall@5 / Faithfulness / 错误拒答率 / 幻觉率 / P95 全部达标才放行。
     *
     * @param recallAt5Mean        平均 Recall@5（非应拒答题）
     * @param faithfulnessMean      平均忠实度（已答题）
     * @param wrongRefusalRate      不应拒答却被拒的比例（误拒）
     * @param hallucinationRate     幻觉率（1 - faithfulness 均值）
     * @param p95LatencyMs          P95 单题耗时
     */
    public static boolean gate(double recallAt5Mean,
                               double faithfulnessMean,
                               double wrongRefusalRate,
                               double hallucinationRate,
                               long p95LatencyMs) {
        return recallAt5Mean >= GATE_RECALL_AT5
                && faithfulnessMean >= GATE_FAITHFULNESS
                && wrongRefusalRate <= GATE_WRONG_REFUSAL_RATE
                && hallucinationRate <= GATE_HALLUCINATION_RATE
                && p95LatencyMs <= GATE_P95_LATENCY_MS;
    }

    private static double log2(double x) {
        return Math.log(x) / Math.log(2.0);
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) {
            return 0.0;
        }
        if (v < 0.0) {
            return 0.0;
        }
        return Math.min(1.0, v);
    }
}
