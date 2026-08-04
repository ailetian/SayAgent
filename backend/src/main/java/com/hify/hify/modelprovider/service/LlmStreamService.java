package com.hify.hify.modelprovider.service;

import com.hify.hify.modelprovider.client.ChatMessage;
import com.hify.hify.modelprovider.client.TokenUsage;
import com.hify.hify.modelprovider.route.ProviderRouter;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 流式对话服务（M6 T3 依赖，补 M3 未发布的流式接口）。
 *
 * <p>大白话：M3 的 {@code ModelService} 只管厂商增删改查、{@code ProviderRouter} 只管单次阻塞调用，
 * 都没有"逐片吐字"的流式接口。T3 需要 Flux 流式输出，故在此统一发布
 * {@link #stream(List, Long, TokenUsage)}：按 Agent 指定的厂商（或默认厂商）逐片返回文本增量，
 * 末片通过 {@code TokenUsage} 回填 token 用量。底层复用 M3 的 ProviderRouter 选主/配置（§4.5）。
 */
@Service
@RequiredArgsConstructor
public class LlmStreamService {

    private final ProviderRouter providerRouter;

    /**
     * 流式取 LLM 输出。
     *
     * @param messages           对话上下文（system/user/assistant）
     * @param preferredProviderId Agent 绑定的厂商 id；为空或不可用时回退默认厂商（§4.5）
     * @param usage              token 用量收集器（末片回填）
     * @return content 增量 Flux（每个元素是一小段文本）
     */
    public Flux<String> stream(List<ChatMessage> messages, Long preferredProviderId, TokenUsage usage) {
        return providerRouter.stream(messages, preferredProviderId, usage);
    }
}
