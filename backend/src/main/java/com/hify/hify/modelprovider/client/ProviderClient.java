package com.hify.hify.modelprovider.client;

import com.hify.hify.modelprovider.domain.enums.ProviderType;

import java.util.List;
import reactor.core.publisher.Flux;

/**
 * 统一「发消息给大模型」接口（§3.1 / §3.5 强类型）。
 *
 * <p>大白话：不管底层是 OpenAI、Claude、Gemini 还是 Ollama，对外都长这张脸——
 * 丢进去一组对话 + 一份配置，吐出来一个 {@link LlmResponse}。路由层（T4）就能无差别切换。
 */
public interface ProviderClient {

    /**
     * 发送对话并拿回模型回复。
     *
     * @param messages 对话历史（用户/助手/系统轮次）
     * @param config   本次调用的配置（含 apiUrl / apiKey / model，来自 T1 的 ModelProvider）
     * @return 统一响应 {@link LlmResponse}
     * @throws com.hify.hify.common.exception.BizException 非 2xx / 超时 / 解析失败，统一抛 {@code ErrorCode.LLM_CALL_FAILED}
     */
    LlmResponse send(List<ChatMessage> messages, ProviderConfig config);

    /** 该 Client 对应的厂商类型，供路由器按 ProviderType 选主/降级（T4）。 */
    ProviderType getType();

    /**
     * 向量化（Embedding）：把一批文本转成同维向量（M5 知识库调用）。
     *
     * <p>大白话：把若干段文字丢给模型厂商的 embeddings 接口，拿回等长的一批浮点向量；
     * 第 i 个向量对应第 i 段文本。维度由 {@link ProviderConfig#getModel()} 对应的 embedding 模型决定
     * （默认 1024，须与 pgvector 的 {@code vector(1024)} 一致）。
     *
     * @param texts  待向量化文本（非空、非 null）
     * @param config 厂商连接配置（baseUrl/apiKey/model/timeoutMs）
     * @return 与 {@code texts} 顺序一一对应的向量列表，长度等于 {@code texts.size()}
     * @throws com.hify.hify.common.exception.BizException 上游异常/超时/解析失败（ErrorCode=EMBEDDING_FAILED）
     */
    List<float[]> embed(List<String> texts, ProviderConfig config);

    /**
     * 流式对话（M6 T3）：逐片返回模型输出增量，主链路 SSE 推送用。
     * 末片若携带 usage，通过 {@code usage} 回写输入/输出 token（供落库与统计）。
     *
     * @param messages 对话历史（用户/助手/系统轮次）
     * @param config   本次调用配置（apiUrl/apiKey/model）
     * @param usage    token 用量收集器（末片回填）
     * @return content 增量 Flux（每个元素是一小段文本）
     */
    Flux<String> stream(List<ChatMessage> messages, ProviderConfig config, TokenUsage usage);
}
