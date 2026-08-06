package com.hify.hify.knowledge.web;

import java.util.List;

/**
 * 题集打分结果（K8）。
 *
 * <p>大白话：逐题跑真实问答后汇总——
 * 总题数 / 已答 / 拒答 / 命中率（topScore≥阈值占比）/ 平均最高分 / 平均耗时；
 * {@code faithfulness} 字段 K8 暂不接评测模型，标记 "N/A（需评测模型）"，由 K10 补齐。
 */
public record EvalResultVO(
        int total,
        int answered,
        int refused,
        double hitRate,
        double avgTopScore,
        double avgCostMs,
        /** 忠实度：K8 未接评测模型，恒为 N/A。 */
        String faithfulness,
        List<EvalItem> items
) {

    /** 单题结果。 */
    public record EvalItem(
            String question,
            boolean refused,
            double topScore,
            int sourcesCount,
            String answerSnippet
    ) {
    }
}
