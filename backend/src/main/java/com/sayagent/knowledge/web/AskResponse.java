package com.sayagent.knowledge.web;

import java.util.List;

/**
 * 问答响应（K8），对应 K5 {@code RagAnswer} 的对外视图。
 *
 * <p>大白话：带答案 + 来源清单。若触发阈值拒答（R3），{@code refused=true}、{@code answer} 为统一拒答话术、
 * {@code refusalReason} 标明分型（NO_KB/NO_HIT/BELOW_THRESHOLD）；{@code sources} 为空。
 *
 * @param refused        是否拒答
 * @param answer         答案文本（拒答时为统一话术）
 * @param refusalReason  拒答分型（拒答时非空，枚举名）
 * @param topScore       候选最高语义余弦分
 * @param threshold      生效的相似度阈值
 * @param sources        溯源引用列表（对应答案里的 [来源i]）
 */
public record AskResponse(
        boolean refused,
        String answer,
        String refusalReason,
        double topScore,
        double threshold,
        List<AskSource> sources
) {

    /** 单个溯源引用（与答案中的 [来源i] 一一对应）。 */
    public record AskSource(int index, String documentId, int seq, String title) {
    }
}
