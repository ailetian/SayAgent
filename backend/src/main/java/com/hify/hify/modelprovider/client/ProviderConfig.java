package com.hify.hify.modelprovider.client;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 单次 LLM 调用配置（§3.5 强类型 DTO，§7.11 秘钥走参数禁止写死）。
 *
 * <p>大白话：把 T1 的 ModelProvider 里「这次调用要用什么」抽出来——接口地址、秘钥、模型名。
 * 秘钥从数据库带来、只在这一次调用里用，绝不落到代码/配置文件里、也绝不打印。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProviderConfig {

    /** 接口地址（来自 ModelProvider.apiUrl，如 https://api.openai.com/v1）。 */
    private String apiUrl;

    /** 秘钥（来自 ModelProvider.secret，§7.11 禁止写死/打印）。 */
    private String apiKey;

    /** 模型名（如 gpt-4o / claude-3-5-sonnet / gemini-1.5-pro / llama3）。 */
    private String model;

    /** 调用超时（毫秒，默认 30000；embedding 调用同样复用）。 */
    @Builder.Default
    private int timeoutMs = 30000;
}
