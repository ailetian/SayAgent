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
import reactor.core.publisher.Flux;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Google Gemini 实现（§3.1 / §4.8 / §7.11）。
 *
 * <p>大白话：Gemini 把 apiKey 放 URL 的 query 参数、model 放路径里；角色用 user/model
 * （assistant 要翻成 model）；system 指令单独放 systemInstruction。响应从 candidates[0].content.parts[0].text 抠正文。
 */
@Component
public class GeminiClient implements ProviderClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient okHttpClient;
    private final ProviderType providerType = ProviderType.GEMINI;

    @Override
    public ProviderType getType() {
        return providerType;
    }

    /**
     * 向量化：调 Gemini :batchEmbedContents 接口，key 走 query 参数，requests[].content.parts[].text 为各段文本。
     * 响应 embeddings[].values 为各段向量（§7.4）。
     */
    @Override
    public List<float[]> embed(List<String> texts, ProviderConfig config) {
        String base = config.getApiUrl().replaceAll("/$", "");
        String url = base + "/models/" + config.getModel() + ":batchEmbedContents?key=" + config.getApiKey();
        var body = MAPPER.createObjectNode();
        var requests = body.putArray("requests");
        for (String t : texts) {
            var r = requests.addObject();
            r.put("model", "models/" + config.getModel());
            var content = r.putObject("content");
            var parts = content.putArray("parts");
            parts.addObject().put("text", t);
        }
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
                vectors.add(toFloat(e.path("values")));
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

    public GeminiClient(OkHttpClient okHttpClient) {
        this.okHttpClient = okHttpClient;
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

    /** 拼装 Gemini 请求：key 作 query 参数，model 进路径。 */
    private Request buildRequest(List<ChatMessage> messages, ProviderConfig config, boolean stream) {
        String base = config.getApiUrl().replaceAll("/$", "");
        String url = base + "/models/" + config.getModel() + ":generateContent?key=" + config.getApiKey();
        RequestBody body = RequestBody.create(buildRequestBody(messages, config, stream), JSON);
        return new Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();
    }

    /** 拼装请求体（assistant->model，system->systemInstruction）。 */
    private String buildRequestBody(List<ChatMessage> messages, ProviderConfig config, boolean stream) {
        try {
            var root = MAPPER.createObjectNode();
            root.put("stream", stream);
            var contents = root.putArray("contents");
            StringBuilder system = new StringBuilder();
            for (ChatMessage m : messages) {
                String role = switch (m.getRole().toLowerCase()) {
                    case "system" -> {
                        system.append(m.getContent()).append("\n");
                        yield null;
                    }
                    case "assistant" -> "model";
                    default -> "user";
                };
                if (role != null) {
                    var c = contents.addObject();
                    c.put("role", role);
                    var parts = c.putArray("parts");
                    parts.addObject().put("text", m.getContent());
                }
            }
            if (system.length() > 0) {
                root.put("systemInstruction", MAPPER.createObjectNode().put("text", system.toString().strip()));
            }
            return MAPPER.writeValueAsString(root);
        } catch (IOException e) {
            throw new BizException(ErrorCode.LLM_CALL_FAILED, "build request body failed");
        }
    }

    /** 解析 Gemini 响应 -> LlmResponse。 */
    private LlmResponse parseResponse(Response response) throws IOException {
        ResponseBody body = response.body();
        if (body == null) {
            throw new BizException(ErrorCode.LLM_CALL_FAILED, "empty response body");
        }
        JsonNode root = MAPPER.readTree(body.string());
        JsonNode candidate = root.path("candidates").path(0);
        String text = candidate.path("content").path("parts").path(0).path("text").asText("");
        String finishReason = candidate.path("finishReason").asText(null);
        JsonNode usage = root.path("usageMetadata");
        Integer promptTokens = usage.has("promptTokenCount") ? usage.get("promptTokenCount").asInt() : null;
        Integer completionTokens = usage.has("candidatesTokenCount") ? usage.get("candidatesTokenCount").asInt() : null;
        return LlmResponse.builder()
                .content(text)
                .finishReason(finishReason)
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .rawStatus(response.code())
                .build();
    }

    /** 拼装 Gemini 流式请求：:streamGenerateContent?alt=sse。 */
    private Request buildStreamRequest(List<ChatMessage> messages, ProviderConfig config) {
        String base = config.getApiUrl().replaceAll("/$", "");
        String url = base + "/models/" + config.getModel() + ":streamGenerateContent?alt=sse&key=" + config.getApiKey();
        RequestBody body = RequestBody.create(buildRequestBody(messages, config, true), JSON);
        return new Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();
    }

    /**
     * 流式对话（M6 T3）：向 :streamGenerateContent?alt=sse 发请求，逐帧解析 SSE 的 data: 行，
     * 把 candidates[0].content.parts[].text 增量经 Flux 吐出；usageMetadata 回填 usage。
     */
    @Override
    public Flux<String> stream(List<ChatMessage> messages, ProviderConfig config, TokenUsage usage) {
        Request request = buildStreamRequest(messages, config);
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(config.getTimeoutMs(), TimeUnit.MILLISECONDS)
                .readTimeout(Math.max(config.getTimeoutMs(), 600_000), TimeUnit.MILLISECONDS)
                .writeTimeout(config.getTimeoutMs(), TimeUnit.MILLISECONDS)
                .build();
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
                        JsonNode candidate = node.path("candidates").path(0);
                        JsonNode text = candidate.path("content").path("parts").path(0).path("text");
                        if (!text.isNull() && !text.asText().isEmpty()) {
                            sink.next(text.asText());
                        }
                        JsonNode usageNode = node.path("usageMetadata");
                        if (!usageNode.isMissingNode()) {
                            int prompt = usageNode.has("promptTokenCount") ? usageNode.get("promptTokenCount").asInt() : 0;
                            int completion = usageNode.has("candidatesTokenCount") ? usageNode.get("candidatesTokenCount").asInt() : 0;
                            usage.setUsage(prompt, completion);
                        }
                    } catch (IOException ex) {
                        log.debug("skip non-json gemini sse line: {}", trimmed);
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
