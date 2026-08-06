package com.hify.hify.knowledge.web;

import jakarta.validation.constraints.NotBlank;

/**
 * 试问台请求（K8 {@code POST /{kbId}/probe}）。
 *
 * <p>大白话：不真正调 LLM 生成答案，只跑一遍「这个问题能不能在库里检索到、命中哪几段」预览，
 * 方便运营人员在上线前验证知识库覆盖度。参数同 ask 的 query。
 */
public record ProbeRequest(

        @NotBlank(message = "query 不能为空")
        String query
) {
}
