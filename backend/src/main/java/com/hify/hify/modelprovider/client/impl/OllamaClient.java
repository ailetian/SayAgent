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
 * 本地 Ollama 实现（§3.1 / §4.8 / §7.11）。
 *
 * <p>大白话：Ollama 是本地部署、免秘钥，接口是 /api/chat，body 里带 model 与 messages、stream=false。
 * 角色直接用 user/assistant/system（Ollama 认）。响应从 message.content 抠正文，token 叫 prompt_eval_count/eval_count。
 */
@Component
public class OllamaClient implements ProviderClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient okHttpClient;
    private final ProviderType providerType = ProviderType.OLLAMA;

    @Override
    public ProviderType getType() {
        return providerType;
    }

    /**
     * 向量化：调 Ollama /api/embed 接口，input 为文本数组（无需 apiKey）。
     * 响应 embeddings 直接是二维数组 [[...],[...]]（§7.4）。
     */
    @Override
    public List<float[]> embed(List<String> texts, ProviderConfig config) {
        String base = config.getApiUrl().replaceAll("/$", "");
        String url = base + "/api/embed";
        var body = MAPPER.createObjectNode();
        body.put("model", config.getModel());
        var input = body.putArray("input");
        texts.forEach(input::add);
        String resp = postJson(url, Map.of(), body, config.getTimeoutMs());
        return parseEmbeddings(resp);
    }

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

    private List<float[]> parseEmbeddings(String resp) {
        try {
            JsonNode root = MAPPER.readTree(resp);
            List<float[]> vectors = new ArrayList<>();
            for (JsonNode e : root.path("embeddings")) {
                vectors.add(toFloat(e));
            }
            return vectors;
        } catch (IOException ex) {
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

    public OllamaClient(OkHttpClient okHttpClient) {
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

    /** 拼装 Ollama 请求：/api/chat + stream=false。 */
    private Request buildRequest(List<ChatMessage> messages, ProviderConfig config) {
        String base = config.getApiUrl().replaceAll("/$", "");
        String url = base + "/api/chat";
        RequestBody body = RequestBody.create(buildRequestBody(messages, config, false), JSON);
        return new Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();
    }

    /** 拼装 Ollama 流式请求：/api/chat + stream=true（逐行 JSON）。 */
    private Request buildStreamRequest(List<ChatMessage> messages, ProviderConfig config) {
        String base = config.getApiUrl().replaceAll("/$", "");
        String url = base + "/api/chat";
        RequestBody body = RequestBody.create(buildRequestBody(messages, config, true), JSON);
        return new Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();
    }

    /** 流式独立 OkHttp 客户端：连接超时用配置值，读超时拉长到 10 分钟。 */
    private OkHttpClient buildStreamClient(int timeoutMs) {
        return new OkHttpClient.Builder()
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(Math.max(timeoutMs, 600_000), TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build();
    }

    /**
     * 流式对话（M6 T3）：向 /api/chat 发 stream=true 请求，逐行解析 JSON 帧，
     * 把 message.content 增量通过 Flux 吐出；done=true 时回填 prompt_eval_count/eval_count。
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
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    try {
                        JsonNode node = MAPPER.readTree(trimmed);
                        JsonNode message = node.get("message");
                        if (message != null) {
                            JsonNode content = message.get("content");
                            if (content != null && !content.isNull()) {
                                String text = content.asText();
                                if (!text.isEmpty()) {
                                    sink.next(text);
                                }
                            }
                        }
                        if (node.get("done") != null && node.get("done").asBoolean(false)) {
                            JsonNode promptEval = node.get("prompt_eval_count");
                            JsonNode eval = node.get("eval_count");
                            if (promptEval != null && eval != null) {
                                usage.setUsage(promptEval.asInt(), eval.asInt());
                            }
                            break;
                        }
                    } catch (IOException ex) {
                        log.debug("skip non-json ollama line: {}", trimmed);
                    }
                }
                sink.complete();
            } catch (IOException e) {
                sink.error(new BizException(ErrorCode.LLM_CALL_FAILED,
                        "provider=" + providerType + " " + e.getMessage()));
            }
        });
    }

    /** 拼装请求体（强类型 -> JSON，禁止裸 Map）。stream=true 时开启流式。 */
    private String buildRequestBody(List<ChatMessage> messages, ProviderConfig config, boolean stream) {
        try {
            var root = MAPPER.createObjectNode();
            root.put("model", config.getModel());
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

    /** 解析 Ollama 响应 -> LlmResponse。 */
    private LlmResponse parseResponse(Response response) throws IOException {
        ResponseBody body = response.body();
        if (body == null) {
            throw new BizException(ErrorCode.LLM_CALL_FAILED, "empty response body");
        }
        JsonNode root = MAPPER.readTree(body.string());
        JsonNode message = root.path("message");
        String text = message.path("content").asText("");
        boolean done = root.path("done").asBoolean(false);
        String finishReason = done ? "stop" : null;
        Integer promptTokens = root.has("prompt_eval_count") ? root.get("prompt_eval_count").asInt() : null;
        Integer completionTokens = root.has("eval_count") ? root.get("eval_count").asInt() : null;
        return LlmResponse.builder()
                .content(text)
                .finishReason(finishReason)
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .rawStatus(response.code())
                .build();
    }
}
