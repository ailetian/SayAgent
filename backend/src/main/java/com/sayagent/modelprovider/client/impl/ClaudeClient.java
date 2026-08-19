package com.sayagent.modelprovider.client.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sayagent.common.exception.BizException;
import com.sayagent.common.exception.ErrorCode;
import com.sayagent.modelprovider.client.ChatMessage;
import com.sayagent.modelprovider.client.LlmResponse;
import com.sayagent.modelprovider.client.ProviderClient;
import com.sayagent.modelprovider.client.ProviderConfig;
import com.sayagent.modelprovider.client.TokenUsage;
import com.sayagent.modelprovider.domain.enums.ProviderType;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import org.slf4j.Logger;
import reactor.core.publisher.Flux;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Anthropic Claude Messages 实现（§3.1 / §4.8 / §7.11）。
 *
 * <p>大白话：Claude 的接口用 x-api-key 头（不是 Bearer），且不接受 messages 里的 system 角色，
 * 需要单独提到顶层 system 字段。响应从 content[0].text 抠正文，token 叫 input/output_tokens。
 */
@Component
public class ClaudeClient implements ProviderClient {

    private static final Logger log = LoggerFactory.getLogger(ClaudeClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient okHttpClient;
    private final OkHttpClient streamOkHttpClient;
    private final ProviderType providerType = ProviderType.CLAUDE;

    @Override
    public ProviderType getType() {
        return providerType;
    }

    /**
     * 向量化：调 Claude /v1/embeddings 接口，input 为文本数组，type=search_document。
     * 响应结构对单条为 {embedding:[...]}，对批量为 {data:[{embedding:[...]}]}，两种都兼容（§7.4）。
     */
    @Override
    public List<float[]> embed(List<String> texts, ProviderConfig config) {
        var body = MAPPER.createObjectNode();
        body.put("model", config.getModel());
        body.put("type", "search_document");
        var input = body.putArray("input");
        texts.forEach(input::add);
        String base = config.getApiUrl().replaceAll("/$", "");
        String url = base + "/v1/embeddings";
        String resp = postJson(url, Map.of("x-api-key", config.getApiKey(), "anthropic-version", "2023-06-01"), body);
        return parseEmbeddings(resp);
    }

    private String postJson(String url, Map<String, String> headers, ObjectNode body) {
        Request.Builder rb = new Request.Builder().url(url)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), JSON));
        headers.forEach(rb::addHeader);
        try (Response response = okHttpClient.newCall(rb.build()).execute()) {
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

    private List<float[]> parseEmbeddings(String resp) {
        try {
            JsonNode root = MAPPER.readTree(resp);
            List<float[]> vectors = new ArrayList<>();
            if (root.has("data")) {
                for (JsonNode item : root.get("data")) {
                    vectors.add(toFloat(item.path("embedding")));
                }
            } else if (root.has("embedding")) {
                vectors.add(toFloat(root.path("embedding")));
            }
            return vectors;
        } catch (IOException e) {
            throw new BizException(ErrorCode.EMBEDDING_FAILED, "parse embedding failed");
        }
    }

    private float[] toFloat(JsonNode arr) {
        float[] v = new float[arr.size()];
        for (int i = 0; i < arr.size(); i++) {
            v[i] = (float) arr.get(i).asDouble();
        }
        return v;
    }

    public ClaudeClient(@Qualifier("okHttpClient") OkHttpClient okHttpClient,
                        @Qualifier("streamOkHttpClient") OkHttpClient streamOkHttpClient) {
        this.okHttpClient = okHttpClient;
        this.streamOkHttpClient = streamOkHttpClient;
    }

    @Override
    public LlmResponse send(List<ChatMessage> messages, ProviderConfig config) {
        Request request = buildRequest(messages, config, false);
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

    /** 拼装 Claude 请求：x-api-key 头 + 把 system 提到顶层。 */
    private Request buildRequest(List<ChatMessage> messages, ProviderConfig config, boolean stream) {
        String base = config.getApiUrl().replaceAll("/$", "");
        String url = base + "/messages";
        RequestBody body = RequestBody.create(buildRequestBody(messages, config, stream), JSON);
        return new Request.Builder()
                .url(url)
                .addHeader("x-api-key", config.getApiKey())
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();
    }

    /** 拼装请求体（system 单独提到顶层，其余进 messages）。 */
    private String buildRequestBody(List<ChatMessage> messages, ProviderConfig config, boolean stream) {
        try {
            var root = MAPPER.createObjectNode();
            root.put("model", config.getModel());
            root.put("max_tokens", 1024);
            root.put("stream", stream);
            StringBuilder system = new StringBuilder();
            var arr = root.putArray("messages");
            for (ChatMessage m : messages) {
                if ("system".equalsIgnoreCase(m.getRole())) {
                    if (system.length() > 0) {
                        system.append("\n");
                    }
                    system.append(m.getContent());
                } else {
                    var node = arr.addObject();
                    node.put("role", m.getRole());
                    node.put("content", m.getContent());
                }
            }
            if (system.length() > 0) {
                root.put("system", system.toString());
            }
            return MAPPER.writeValueAsString(root);
        } catch (IOException e) {
            throw new BizException(ErrorCode.LLM_CALL_FAILED, "build request body failed");
        }
    }

    /** 解析 Claude 响应 -> LlmResponse。 */
    private LlmResponse parseResponse(Response response) throws IOException {
        ResponseBody body = response.body();
        if (body == null) {
            throw new BizException(ErrorCode.LLM_CALL_FAILED, "empty response body");
        }
        JsonNode root = MAPPER.readTree(body.string());
        JsonNode content = root.path("content").path(0);
        String text = content.path("text").asText("");
        String finishReason = root.path("stop_reason").asText(null);
        JsonNode usage = root.path("usage");
        Integer promptTokens = usage.has("input_tokens") ? usage.get("input_tokens").asInt() : null;
        Integer completionTokens = usage.has("output_tokens") ? usage.get("output_tokens").asInt() : null;
        return LlmResponse.builder()
                .content(text)
                .finishReason(finishReason)
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .rawStatus(response.code())
                .build();
    }

    /** 拼装 Claude 流式请求：/messages + stream=true（SSE 帧）。 */
    private Request buildStreamRequest(List<ChatMessage> messages, ProviderConfig config) {
        String base = config.getApiUrl().replaceAll("/$", "");
        String url = base + "/messages";
        RequestBody body = RequestBody.create(buildRequestBody(messages, config, true), JSON);
        return new Request.Builder()
                .url(url)
                .addHeader("x-api-key", config.getApiKey())
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();
    }

    /**
     * 流式对话（M6 T3）：向 /messages 发 stream=true 请求，逐帧解析 SSE，
     * 把 content_block_delta.delta.text 增量经 Flux 吐出；message_delta 回填 usage。
     */
    @Override
    public Flux<String> stream(List<ChatMessage> messages, ProviderConfig config, TokenUsage usage) {
        Request request = buildStreamRequest(messages, config);
        OkHttpClient client = this.streamOkHttpClient;
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
                    if (data.isEmpty()) {
                        continue;
                    }
                    try {
                        JsonNode node = MAPPER.readTree(data);
                        String type = node.path("type").asText("");
                        if ("content_block_delta".equals(type)) {
                            JsonNode text = node.path("delta").path("text");
                            if (text != null && !text.isNull() && !text.asText().isEmpty()) {
                                sink.next(text.asText());
                            }
                        } else if ("message_start".equals(type)) {
                            JsonNode inputTokens = node.path("message").path("usage").path("input_tokens");
                            if (!inputTokens.isMissingNode()) {
                                usage.setUsage(inputTokens.asInt(), usage.getCompletionTokens());
                            }
                        } else if ("message_delta".equals(type)) {
                            JsonNode outputTokens = node.path("usage").path("output_tokens");
                            if (!outputTokens.isMissingNode()) {
                                usage.setUsage(usage.getPromptTokens(), outputTokens.asInt());
                            }
                        }
                    } catch (IOException ex) {
                        log.debug("skip non-json claude sse line: {}", trimmed);
                    }
                }
                sink.complete();
            } catch (IOException e) {
                sink.error(new BizException(ErrorCode.LLM_CALL_FAILED,
                        "provider=" + providerType + " " + e.getMessage()));
            }
        });
    }
}
