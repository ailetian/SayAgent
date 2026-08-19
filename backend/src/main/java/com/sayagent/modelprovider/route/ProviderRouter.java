package com.sayagent.modelprovider.route;

import com.sayagent.common.exception.BizException;
import com.sayagent.common.exception.ErrorCode;
import com.sayagent.common.tool.ToolDefinition;
import com.sayagent.modelprovider.client.ChatMessage;
import com.sayagent.modelprovider.client.LlmResponse;
import com.sayagent.modelprovider.client.ProviderClient;
import com.sayagent.modelprovider.client.ProviderConfig;
import com.sayagent.modelprovider.client.TokenUsage;
import com.sayagent.modelprovider.domain.enums.ProviderType;
import com.sayagent.modelprovider.entity.ModelProvider;
import com.sayagent.modelprovider.repository.ModelProviderRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import reactor.core.publisher.Flux;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 模型路由 + 降级链（§4.5 降级 / §7.4 异常 / §4.9 可观测）。
 *
 * <p>大白话：默认模型按 is_default + sort_order 选；主模型抛异常就自动切下一个 enabled 的 Provider，
 * 直到成功或全失败。每次切换打 WARN（哪个 provider 失败、切到哪个），底层异常带上下文不吞（§7.4）。
 */
@Component
public class ProviderRouter {

    private static final Logger log = LoggerFactory.getLogger(ProviderRouter.class);

    private final ModelProviderRepository repository;
    private final ResilienceDecorator decorator;
    private final Map<ProviderType, ProviderClient> clientMap;

    public ProviderRouter(ModelProviderRepository repository,
                          ResilienceDecorator decorator,
                          List<ProviderClient> clients) {
        this.repository = repository;
        this.decorator = decorator;
        this.clientMap = clients.stream()
                .collect(Collectors.toMap(ProviderClient::getType, c -> c));
    }

    /** 选主模型并发请求，主失败自动降级到下一个 enabled Provider。 */
    public LlmResponse route(List<ChatMessage> messages) {
        List<ModelProvider> enabled = repository.findAllByEnabledTrueOrderBySortOrderAsc();
        if (enabled.isEmpty()) {
            throw new BizException(ErrorCode.LLM_ALL_PROVIDERS_FAILED, "no enabled model provider configured");
        }
        List<ModelProvider> ordered = orderWithDefaultFirst(enabled);
        for (int i = 0; i < ordered.size(); i++) {
            ModelProvider provider = ordered.get(i);
            ProviderClient client = clientMap.get(provider.getProviderType());
            if (client == null) {
                log.warn("llm route skip: no client registered for providerType={}", provider.getProviderType());
                continue;
            }
            ProviderConfig config = toConfig(provider);
            ProviderClient decorated = decorator.decorate(client, provider.getProviderType());
            long startNanos = System.nanoTime();
            try {
                LlmResponse resp = decorated.send(messages, config);
                long costMs = (System.nanoTime() - startNanos) / 1_000_000;
                // §4.9/§7.4 可观测：每次成功调用打 INFO，7 字段齐全（provider/model/costMs/inTok/outTok/fallback/ok）；
                // 占位符写法，绝不拼 api_key/token（§7.11 规则37）；fallback 标记是否命中降级（i>0 即降级）。
                log.info("llm.call provider={} model={} costMs={} inTok={} outTok={} fallback={} ok={}",
                        provider.getProviderType(), provider.getModel(), costMs,
                        resp.getPromptTokens(), resp.getCompletionTokens(), i > 0, true);
                return resp;
            } catch (Exception e) {
                long costMs = (System.nanoTime() - startNanos) / 1_000_000;
                // §4.9：失败调用同样打 INFO（ok=false）便于成本/质量分析，并额外 ERROR 保留堆栈（§7.4 规则17）
                log.info("llm.call provider={} model={} costMs={} inTok={} outTok={} fallback={} ok={}",
                        provider.getProviderType(), provider.getModel(), costMs, null, null, i > 0, false);
                log.error("llm.call failed, fallback to next: provider={} sortOrder={}",
                        provider.getProviderType(), provider.getSortOrder(), e);
            }
        }
        throw new BizException(ErrorCode.LLM_ALL_PROVIDERS_FAILED, "all enabled model providers failed");
    }

    /**
     * 流式对话（M6 T3）：按 preferredProviderId 选定厂商后直连其 client.stream，
     * 不做熔断/超时包裹（未来式取消语义复杂，流式超时由连接/读超时控制）。
     * 若 preferredProviderId 为空或对应厂商不可用，则回退到默认厂商（§4.5 选主逻辑）。
     */
    public Flux<String> stream(List<ChatMessage> messages, Long preferredProviderId, TokenUsage usage) {
        ModelProvider provider = resolveProvider(preferredProviderId);
        ProviderClient client = clientMap.get(provider.getProviderType());
        if (client == null) {
            throw new BizException(ErrorCode.MODEL_NOT_FOUND,
                    "未注册流式客户端 providerType=" + provider.getProviderType());
        }
        ProviderConfig config = toConfig(provider);
        return client.stream(messages, config, usage);
    }

    /**
     * 带工具列表的非流式对话（M8/T3 函数调用）：按 preferredProviderId 选厂商后直连其 client.send(带工具)，
     * 让模型有机会回 tool_calls。复用 resolveProvider + clientMap（§4.5 选主）。
     *
     * @param messages        对话上下文
     * @param preferredProviderId Agent 绑定的厂商 id（为空/不可用回退默认）
     * @param toolDefs        工具名片列表（common.tool.ToolDefinition，供模型选工具）
     * @return 非流式响应（可能含 toolCalls）
     */
    public LlmResponse routeWithTools(List<ChatMessage> messages, Long preferredProviderId,
                                      List<ToolDefinition> toolDefs) {
        ModelProvider provider = resolveProvider(preferredProviderId);
        ProviderClient client = clientMap.get(provider.getProviderType());
        if (client == null) {
            throw new BizException(ErrorCode.MODEL_NOT_FOUND,
                    "未注册对话客户端 providerType=" + provider.getProviderType());
        }
        ProviderConfig config = toConfig(provider);
        ProviderClient decorated = decorator.decorate(client, provider.getProviderType());
        return decorated.send(messages, config, toolDefs);
    }

    /**
     * 带工具列表的流式对话（M8/T3 修复流式回归）：按 preferredProviderId 选厂商后直连其 client.sendStreamWithTools，
     * 让模型在生成过程中每吐一个字就经 {@code tokenSink} 回传前端（真正逐字流式），同时把工具调用意图合并回传，
     * 供上层编排循环判断 finish_reason / 是否还要调工具。不做熔断包裹（与 {@link #stream} 一致，流式超时由连接/读超时控制）。
     *
     * @param messages           对话上下文
     * @param preferredProviderId Agent 绑定的厂商 id（为空/不可用回退默认）
     * @param toolDefs           工具名片列表（common.tool.ToolDefinition，供模型选工具）
     * @param tokenSink          内容增量消费者（实时推前端用）
     * @return 完整响应（含可能的 toolCalls 与本轮 token 用量）
     */
    public LlmResponse routeWithToolsStream(List<ChatMessage> messages, Long preferredProviderId,
                                            List<ToolDefinition> toolDefs, Consumer<String> tokenSink) {
        ModelProvider provider = resolveProvider(preferredProviderId);
        ProviderClient client = clientMap.get(provider.getProviderType());
        if (client == null) {
            throw new BizException(ErrorCode.MODEL_NOT_FOUND,
                    "未注册对话客户端 providerType=" + provider.getProviderType());
        }
        ProviderConfig config = toConfig(provider);
        return client.sendStreamWithTools(messages, config, toolDefs, tokenSink);
    }

    /** 解析流式目标厂商：优先用 preferredProviderId（须 enabled），否则用默认厂商（§4.5）。 */
    private ModelProvider resolveProvider(Long preferredProviderId) {
        if (preferredProviderId != null) {
            return repository.findById(preferredProviderId)
                    .filter(p -> Boolean.TRUE.equals(p.getEnabled()))
                    .orElseGet(this::defaultProvider);
        }
        return defaultProvider();
    }

    /** 默认厂商：default_model=true 优先，否则取 sort_order 最前的启用厂商。 */
    private ModelProvider defaultProvider() {
        List<ModelProvider> enabled = repository.findAllByEnabledTrueOrderBySortOrderAsc();
        if (enabled.isEmpty()) {
            throw new BizException(ErrorCode.LLM_ALL_PROVIDERS_FAILED, "no enabled model provider configured");
        }
        return enabled.stream()
                .filter(ModelProvider::getDefaultModel)
                .findFirst()
                .orElse(enabled.get(0));
    }

    /** 默认模型排第一，其余按 sortOrder 升序跟随（§4.5 降级顺序）。 */
    private List<ModelProvider> orderWithDefaultFirst(List<ModelProvider> enabled) {
        ModelProvider primary = enabled.stream()
                .filter(ModelProvider::getDefaultModel)
                .findFirst()
                .orElse(enabled.get(0));
        List<ModelProvider> ordered = new ArrayList<>();
        ordered.add(primary);
        for (ModelProvider p : enabled) {
            if (!Objects.equals(p.getId(), primary.getId())) {
                ordered.add(p);
            }
        }
        return ordered;
    }

    /** 把 ModelProvider 抽成单次调用配置（秘钥只在此使用，绝不落库/打印，§7.11）。 */
    private ProviderConfig toConfig(ModelProvider provider) {
        return ProviderConfig.builder()
                .apiUrl(provider.getApiUrl())
                .apiKey(provider.getSecret())
                .model(provider.getModel())
                .timeoutMs(30000)
                .build();
    }

    /**
     * 默认 embedding 客户端（已套容错）：优先 default_model=true 的启用模型，否则取 sort_order 最前的启用模型。
     * 供 M5 知识库 EmbeddingService 调用，knowledge 模块不感知具体厂商（§3.3 解耦）。
     */
    public ProviderClient getEmbeddingClient() {
        ProviderClient client = clientMap.get(resolveEmbeddingProvider().getProviderType());
        if (client == null) {
            throw new BizException(ErrorCode.MODEL_NOT_FOUND, "未注册 embedding 客户端");
        }
        return decorator.decorate(client, client.getType());
    }

    /** 默认 embedding 模型的连接配置（apiUrl/apiKey/model）。 */
    public ProviderConfig getEmbeddingConfig() {
        return toConfig(resolveEmbeddingProvider());
    }

    /**
     * 默认对话（生成）模型的连接配置（apiUrl/apiKey/model）。
     *
     * <p>供 knowledge 模块 RAG 问答（K5/K8）生成答案时使用——只把「这次调用要什么」抽成 {@link ProviderConfig}，
     * 秘钥仍只在 {@code toConfig} 内部从数据库取出、绝不落库/打印（§7.11）。选取逻辑复用 {@code defaultProvider()}：
     * 优先 {@code default_model=true} 的启用模型，否则取 {@code sort_order} 最前的启用模型。
     */
    public ProviderConfig getDefaultChatConfig() {
        return toConfig(defaultProvider());
    }

    /** 选默认 embedding 模型：优先本地 BGE-M3（设计：Embedding=BGE-M3 1024维，Ollama 本地化，不占外部 key/不吃额外内存）；找不到再退化为 default_model / 首个启用模型。 */
    private ModelProvider resolveEmbeddingProvider() {
        List<ModelProvider> all = repository.findAll();
        return all.stream()
                .filter(p -> Boolean.TRUE.equals(p.getEnabled())
                        && p.getModel() != null
                        && p.getModel().toLowerCase().contains("bge-m3"))
                .min(Comparator.comparingInt(p -> p.getSortOrder() == null ? Integer.MAX_VALUE : p.getSortOrder()))
                .orElseGet(() -> all.stream()
                        .filter(p -> Boolean.TRUE.equals(p.getEnabled()) && Boolean.TRUE.equals(p.getDefaultModel()))
                        .min(Comparator.comparingInt(p -> p.getSortOrder() == null ? Integer.MAX_VALUE : p.getSortOrder()))
                        .orElseGet(() -> all.stream()
                                .filter(p -> Boolean.TRUE.equals(p.getEnabled()))
                                .min(Comparator.comparingInt(p -> p.getSortOrder() == null ? Integer.MAX_VALUE : p.getSortOrder()))
                                .orElseThrow(() -> new BizException(ErrorCode.MODEL_NOT_FOUND, "未配置可用的 embedding 模型"))));
    }
}
