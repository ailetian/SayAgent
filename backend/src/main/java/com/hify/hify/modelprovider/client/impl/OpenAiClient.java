package com.hify.hify.modelprovider.client.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.hify.common.exception.BizException;
import com.hify.hify.common.exception.ErrorCode;
import com.hify.hify.modelprovider.client.ChatMessage;
import com.hify.hify.modelprovider.client.LlmResponse;
import com.hify.hify.modelprovider.client.ProviderClient;
import com.hify.hify.modelprovider.client.ProviderConfig;
import com.hify.hify.modelprovider.client.TokenUsage;
import com.hify.hify.modelprovider.domain.enums.ProviderType;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import reactor.core.publisher.Flux;

/**
 * OpenAI Chat Completions 实现（§3.1 / §4.8 / §7.11）。
 *
 * <p>大白话：把统一接口翻译成 OpenAI 格式——请求体是 messages 数组，秘钥走 Authorization 头（绝不打印），
 * 响应从 choices[0].message.content 抠出正文。拼装与解析分两个方法，保持低复杂度。
 */
@Component
public class OpenAiClient implements ProviderClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient okHttpClient;
    private final ProviderType providerType = ProviderType.OPENAI;

    @Override
    public ProviderType getType() {
        return providerType;
    }

    /**
     * 向量化：调 OpenAI /embeddings 接口，input 为文本数组，响应 data[].embedding 为各段向量。
     * 解析异常/非 2xx 统一抛 {@code EMBEDDING_FAILED}（§7.4）。
     */
    @Override
    public List<float[]> embed(List<String> texts, ProviderConfig config) {
        var body = MAPPER.createObjectNode();
        body.put("model", config.getModel());
        var input = body.putArray("input");
        texts.forEach(input::add);
        String base = config.getApiUrl().replaceAll("/$", "");
        String url = base + "/embeddings";
        String resp = postJson(url, Map.of("Authorization", "Bearer " + config.getApiKey()), body, config.getTimeoutMs());
        return parseEmbeddings(resp);
    }

    /** 发起 JSON POST（embed 复用，超时取 ProviderConfig，失败抛 EMBEDDING_FAILED）。 */
    private String postJson(String url, Map<String, String> headers, ObjectNode body, int timeoutMs) {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build();
        Request.Builder rb = new Request.Builder().url(url)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), JSON));
        headers.forEach(rb::addHeader);
        try (Response response = client.newCall(rb.build()).execute()) {
            String respBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new BizException(ErrorCode.EMBEDDING_FAILED,
                        "provider=" + providerType + " status=" + response.code(), response.code());
            }
            return respBody;
        } catch (IOException e) {
            throw new BizException(ErrorCode.EMBEDDING_FAILED, "provider=" + providerType + " " + e.getMessage());
        }
    }

    /** 解析 OpenAI embeddings 响应（data[].embedding）。 */
    private List<float[]> parseEmbeddings(String resp) {
        try {
            JsonNode root = MAPPER.readTree(resp);
            List<float[]> vectors = new ArrayList<>();
            for (JsonNode item : root.path("data")) {
                vectors.add(toFloat(item.path("embedding")));
            }
            return vectors;
        } catch (IOException e) {
            throw new BizException(ErrorCode.EMBEDDING_FAILED, "parse embedding failed");
        }
    }

    /** JsonNode 数组 -> float[]（维度随模型，默认 1024）。 */
    private float[] toFloat(JsonNode arr) {
        float[] v = new float[arr.size()];
        for (int i = 0; i < arr.size(); i++) {
            v[i] = (float) arr.get(i).asDouble();
        }
        return v;
    }

    public OpenAiClient(OkHttpClient okHttpClient) {
        this.okHttpClient = okHttpClient;
    }

    @Override
    public LlmResponse send(List<ChatMessage> messages, ProviderConfig config) {
        Request request = buildRequest(messages, config);
        try (Response response = okHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.warn("llm call failed: provider={} status={}", providerType, response.code());
                throw new BizException(ErrorCode.LLM_CALL_FAILED,
                        "provider=" + providerType + " status=" + response.code(), response.code());
            }
            return parseResponse(response);
        } catch (IOException e) {
            log.error("llm call io error: provider={}", providerType, e);
            throw new BizException(ErrorCode.LLM_CALL_FAILED,
                    "provider=" + providerType + " " + e.getMessage());
        }
    }

    /** 拼装 OpenAI 请求：URL + Bearer 头 + messages 体（非流式）。 */
    private Request buildRequest(List<ChatMessage> messages, ProviderConfig config) {
        String base = config.getApiUrl().replaceAll("/$", "");
        String url = base + "/chat/completions";
        RequestBody body = RequestBody.create(buildRequestBody(messages, config, false), JSON);
        return new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + config.getApiKey())
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();
    }

    /** 拼装 OpenAI 流式请求：/chat/completions + stream=true（SSE）。 */
    private Request buildStreamRequest(List<ChatMessage> messages, ProviderConfig config) {
        String base = config.getApiUrl().replaceAll("/$", "");
        String url = base + "/chat/completions";
        RequestBody body = RequestBody.create(buildRequestBody(messages, config, true), JSON);
        return new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + config.getApiKey())
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();
    }

    /** 流式独立 OkHttp 客户端：连接超时用配置值，读超时拉长到 10 分钟（流式 Output 不能按普通请求超时切断）。 */
    private OkHttpClient buildStreamClient(int timeoutMs) {
        return new OkHttpClient.Builder()
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(Math.max(timeoutMs, 600_000), TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build();
    }

    /**
     * 流式对话（M6 T3）：向 /chat/completions 发 stream=true 请求，逐行解析 SSE 的 data: 帧，
     * 把 choices[0].delta.content 增量通过 Flux 吐出；若末片带 usage 则回填 {@code usage}。
     */
    @Override
    public Flux<String> stream(List<ChatMessage> messages, ProviderConfig config, TokenUsage usage) {
        Request request = buildStreamRequest(messages, config);
        OkHttpClient client = buildStreamClient(config.getTimeoutMs());
        return Flux.create(sink -> {
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    sink.error(new BizException(ErrorCode.LLM_CALL_FAILED,
                            "provider=" + providerType + " status=" + response.code(), response.code()));
                    return;
                }
                ResponseBody body = response.body();
                if (body == null) {
                    sink.error(new BizException(ErrorCode.LLM_CALL_FAILED, "empty response body"));
                    return;
                }
                BufferedSource source = body.source();
                String line;
                while (!sink.isCancelled() && (line = source.readUtf8Line()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || !trimmed.startsWith("data:")) {
                        continue;
                    }
                    String data = trimmed.substring(5).trim();
                    if ("[DONE]".equals(data)) {
                        break;
                    }
                    try {
                        JsonNode node = MAPPER.readTree(data);
                        JsonNode choices = node.get("choices");
                        if (choices != null && choices.isArray() && choices.size() > 0) {
                            JsonNode delta = choices.get(0).get("delta");
                            if (delta != null) {
                                JsonNode content = delta.get("content");
                                if (content != null && !content.isNull()) {
                                    String text = content.asText();
                                    if (!text.isEmpty()) {
                                        sink.next(text);
                                    }
                                }
                            }
                        }
                        JsonNode usageNode = node.get("usage");
                        if (usageNode != null && !usageNode.isNull()) {
                            int prompt = usageNode.has("prompt_tokens") ? usageNode.get("prompt_tokens").asInt() : 0;
                            int completion = usageNode.has("completion_tokens") ? usageNode.get("completion_tokens").asInt() : 0;
                            usage.setUsage(prompt, completion);
                        }
                    } catch (IOException ex) {
                        // 跳过心跳/非法行（如 ": keep-alive"），不中断流
                        log.debug("skip non-json sse line: {}", trimmed);
                    }
                }
                sink.complete();
            } catch (IOException e) {
                sink.error(new BizException(ErrorCode.LLM_CALL_FAILED,
                        "provider=" + providerType + " " + e.getMessage()));
            }
        });
    }

    /** 拼装请求体（强类型 -> JSON，禁止裸 Map）。stream=true 时追加 stream 字段开启 SSE 流式。 */
    private String buildRequestBody(List<ChatMessage> messages, ProviderConfig config, boolean stream) {
        try {
            var root = MAPPER.createObjectNode();
            root.put("model", config.getModel());
            root.put("temperature", 0.7);
            root.put("stream", stream);
            var arr = root.putArray("messages");
            for (ChatMessage m : messages) {
                var node = arr.addObject();
                node.put("role", m.getRole());
                node.put("content", m.getContent());
            }
            return MAPPER.writeValueAsString(root);
        } catch (IOException e) {
            throw new BizException(ErrorCode.LLM_CALL_FAILED, "build request body failed");
        }
    }

    /** 解析 OpenAI 响应 -> LlmResponse。 */
    private LlmResponse parseResponse(Response response) throws IOException {
        ResponseBody body = response.body();
        if (body == null) {
            throw new BizException(ErrorCode.LLM_CALL_FAILED, "empty response body");
        }
        JsonNode root = MAPPER.readTree(body.string());
        JsonNode choice = root.path("choices").path(0);
        String content = choice.path("message").path("content").asText("");
        String finishReason = choice.path("finish_reason").asText(null);
        JsonNode usage = root.path("usage");
        Integer promptTokens = usage.has("prompt_tokens") ? usage.get("prompt_tokens").asInt() : null;
        Integer completionTokens = usage.has("completion_tokens") ? usage.get("completion_tokens").asInt() : null;
        return LlmResponse.builder()
                .content(content)
                .finishReason(finishReason)
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .rawStatus(response.code())
                .build();
    }
}
