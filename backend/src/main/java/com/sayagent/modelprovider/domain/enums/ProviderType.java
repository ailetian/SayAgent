package com.sayagent.modelprovider.domain.enums;

/**
 * 模型厂商类型枚举（§3.2 / §4.5）。
 *
 * <p>大白话：给"用哪家大模型"四个固定选项——OpenAI / Claude / Gemini / Ollama（本地）。
 * 存库用字符串（便于阅读与迁移，§4.5）；路由降级链按此枚举顺序切备用（§4.5 第3条）。
 */
public enum ProviderType {
    OPENAI,
    CLAUDE,
    GEMINI,
    OLLAMA
}
