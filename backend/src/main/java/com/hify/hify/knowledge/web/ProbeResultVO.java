package com.hify.hify.knowledge.web;

import java.util.List;

/**
 * 试问台结果（K8），即「检索预览」。
 *
 * <p>大白话：{@code hit} 表示这个问题按当前阈值会不会被判定「能答」；
 * {@code candidates} 列出命中的前几段（含文档/段落/分数/片段预览），便于人工核对召回质量。
 */
public record ProbeResultVO(
        boolean hit,
        double topScore,
        double threshold,
        List<ProbeCandidate> candidates
) {

    /** 单个候选片段预览。 */
    public record ProbeCandidate(String documentId, int seq, double score, String snippet) {
    }
}
