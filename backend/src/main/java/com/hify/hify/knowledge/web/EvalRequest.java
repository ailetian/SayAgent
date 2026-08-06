package com.hify.hify.knowledge.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 题集打分请求（K8 {@code POST /{kbId}/eval}）。
 *
 * <p>大白话：把一批「问题 + 期望/是否应拒答」的题集发过来，系统逐题跑一遍真实问答，
 * 汇总命中率 / 拒答率 / 平均耗时等指标（K10 门禁的轻量版，K8 先提供可用的题集接口）。
 */
public record EvalRequest(

        @NotNull(message = "题集不能为空")
        List<EvalQuestion> questions
) {

    /** 题集中的单题。 */
    public record EvalQuestion(
            @NotBlank(message = "题目 question 不能为空")
            String question,
            /** 期望答案摘要（可选，K8 仅记录、不参与自动判分）。 */
            String expected,
            /** 是否期望被拒答（可选，用于拒答率校验）。 */
            Boolean shouldReject
    ) {
    }
}
