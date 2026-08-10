package com.hify.hify.modelprovider.client.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.hify.common.exception.BizException;
import com.hify.hify.common.exception.ErrorCode;
import com.hify.hify.common.tool.ToolCall;
import com.hify.hify.common.tool.ToolDefinition;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
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
        return send(messages, config, List.of());
    }

    /** 带工具列表的发送（M8/T2 函数调用）：把工具名片拼进请求，模型可回 tool_calls。 */
    @Override
    public LlmResponse send(List<ChatMessage> messages, ProviderConfig config, List<ToolDefinition> tools) {
        Request request = buildRequest(messages, config, tools);
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

    /** 拼装 OpenAI 请求：URL + Bearer 头 + messages 体（非流式，可带工具）。 */
    private Request buildRequest(List<ChatMessage> messages, ProviderConfig config, List<ToolDefinition> tools) {
        String base = config.getApiUrl().replaceAll("/$", "");
        String url = base + "/chat/completions";
        RequestBody body = RequestBody.create(buildRequestBody(messages, config, false, tools), JSON);
        return new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + config.getApiKey())
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();
    }

    /** 拼装 OpenAI 流式请求：/chat/completions + stream=true（SSE）；流式暂不做函数调用，tools 传空。 */
    private Request buildStreamRequest(List<ChatMessage> messages, ProviderConfig config) {
        String base = config.getApiUrl().replaceAll("/$", "");
        String url = base + "/chat/completions";
        RequestBody body = RequestBody.create(buildRequestBody(messages, config, true, List.of()), JSON);
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

    /**
     * 流式 + 函数调用（M8/T3 修复「流式回归」）：向 /chat/completions 发 stream=true 且携带 tools 的请求，
     * 边收 SSE 边把 choices[0].delta.content 增量经 {@code tokenSink} 实时推给前端（真正逐字流式），
     * 同时把流式下发的 tool_calls 碎片（按 index 累计 id/type/function.name/function.arguments）合并成完整列表，
     * 末片返回 {@link LlmResponse}（供编排循环判断 finish_reason / 是否还要调工具）。
     * 这样 LLM 在生成过程中每个字都即时可见，不再等到整段生成完才切片假流式（解决 KB 后长时间空白）。
     */
    @Override
    public LlmResponse sendStreamWithTools(List<ChatMessage> messages, ProviderConfig config,
                                           List<ToolDefinition> tools, Consumer<String> tokenSink) {
        String base = config.getApiUrl().replaceAll("/$", "");
        String url = base + "/chat/completions";
        RequestBody body = RequestBody.create(buildRequestBody(messages, config, true, tools), JSON);
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + config.getApiKey())
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();
        OkHttpClient client = buildStreamClient(config.getTimeoutMs());
        // 合并流式 tool_calls 碎片
        Map<Integer, ToolCallAcc> acc = new HashMap<>();
        StringBuilder content = new StringBuilder();
        int roundPrompt = 0;
        int roundCompletion = 0;
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new BizException(ErrorCode.LLM_CALL_FAILED,
                        "provider=" + providerType + " status=" + response.code(), response.code());
            }
            ResponseBody respBody = response.body();
            if (respBody == null) {
                throw new BizException(ErrorCode.LLM_CALL_FAILED, "empty response body");
            }
            BufferedSource source = respBody.source();
            String line;
            while (!Thread.currentThread().isInterrupted() && (line = source.readUtf8Line()) != null) {
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
                            JsonNode contentNode = delta.get("content");
                            if (contentNode != null && !contentNode.isNull()) {
                                String text = contentNode.asText();
                                if (!text.isEmpty()) {
                                    content.append(text);
                                    if (tokenSink != null) {
                                        tokenSink.accept(text);
                                    }
                                }
                            }
                            JsonNode tcDelta = delta.get("tool_calls");
                            if (tcDelta != null && tcDelta.isArray()) {
                                for (JsonNode tcd : tcDelta) {
                                    int idx = tcd.has("index") ? tcd.get("index").asInt(0) : 0;
                                    ToolCallAcc a = acc.computeIfAbsent(idx, k -> new ToolCallAcc());
                                    if (tcd.has("id")) {
                                        a.id = tcd.get("id").asText(null);
                                    }
                                    if (tcd.has("type")) {
                                        a.type = tcd.get("type").asText(null);
                                    }
                                    JsonNode fn = tcd.get("function");
                                    if (fn != null) {
                                        if (fn.has("name")) {
                                            a.name = (a.name == null ? "" : a.name) + fn.get("name").asText("");
                                        }
                                        if (fn.has("arguments")) {
                                            a.args = (a.args == null ? "" : a.args) + fn.get("arguments").asText("");
                                        }
                                    }
                                }
                            }
                        }
                    }
                    JsonNode usageNode = node.get("usage");
                    if (usageNode != null && !usageNode.isNull()) {
                        roundPrompt = usageNode.has("prompt_tokens") ? usageNode.get("prompt_tokens").asInt() : 0;
                        roundCompletion = usageNode.has("completion_tokens") ? usageNode.get("completion_tokens").asInt() : 0;
                    }
                } catch (IOException ex) {
                    // 跳过心跳/非法行（如 ": keep-alive"），不中断流
                    log.debug("skip non-json sse line: {}", trimmed);
                }
            }
        } catch (IOException e) {
            throw new BizException(ErrorCode.LLM_CALL_FAILED,
                    "provider=" + providerType + " " + e.getMessage());
        }
        List<ToolCall> toolCalls = acc.isEmpty() ? null : mergeToolCalls(acc);
        String finishReason = toolCalls == null ? "stop" : "tool_calls";
        return LlmResponse.builder()
                .content(content.length() == 0 ? null : content.toString())
                .finishReason(finishReason)
                .toolCalls(toolCalls)
                .promptTokens(roundPrompt == 0 ? null : roundPrompt)
                .completionTokens(roundCompletion == 0 ? null : roundCompletion)
                .rawStatus(200)
                .build();
    }

    /** 流式 tool_calls 碎片累加器（按 index 累计 id/type/function.name/function.arguments）。 */
    private static final class ToolCallAcc {
        String id;
        String type;
        String name;
        String args;
    }

    /** 按 index 顺序把碎片合并成完整 ToolCall 列表。 */
    private List<ToolCall> mergeToolCalls(Map<Integer, ToolCallAcc> acc) {
        List<Integer> idxs = new ArrayList<>(acc.keySet());
        idxs.sort(Integer::compareTo);
        List<ToolCall> calls = new ArrayList<>();
        for (Integer idx : idxs) {
            ToolCallAcc a = acc.get(idx);
            calls.add(new ToolCall(a.id, a.type, a.name, a.args));
        }
        return calls;
    }

    /** 拼装请求体（强类型 -> JSON，禁止裸 Map）。stream=true 时追加 stream 字段开启 SSE 流式。
     *  tools 非空时附加 OpenAI function-calling 的 tools 数组；assistant 带 tool_calls、tool 角色带 tool_call_id 一并序列化。
     *  方法 public（M8/T2）：放宽为包外可见，单测在 modelprovider.client 包直接构造 JsonNode 调用、不依赖真实 HTTP。 */
    public String buildRequestBody(List<ChatMessage> messages, ProviderConfig config, boolean stream, List<ToolDefinition> tools) {
        try {
            var root = MAPPER.createObjectNode();
            root.put("model", config.getModel());
            root.put("temperature", 0.7);
            root.put("stream", stream);
            var arr = root.putArray("messages");
            for (ChatMessage m : messages) {
                var node = arr.addObject();
                node.put("role", m.getRole());
                // content：tool 角色必带结果；assistant 带 tool_calls 时可能为 null，仍保留字段
                if (m.getContent() != null) {
                    node.put("content", m.getContent());
                } else {
                    node.putNull("content");
                }
                if (m.getToolCallId() != null) {
                    node.put("tool_call_id", m.getToolCallId());
                }
                if (m.getName() != null) {
                    node.put("name", m.getName());
                }
                List<ToolCall> calls = m.getToolCalls();
                if (calls != null && !calls.isEmpty()) {
                    var tcArr = node.putArray("tool_calls");
                    for (ToolCall tc : calls) {
                        var t = tcArr.addObject();
                        t.put("id", tc.id());
                        t.put("type", tc.type() != null ? tc.type() : "function");
                        var fn = t.putObject("function");
                        fn.put("name", tc.functionName());
                        fn.set("arguments", parseArgs(tc.argumentsJson()));
                    }
                }
            }
            if (tools != null && !tools.isEmpty()) {
                var toolsArr = root.putArray("tools");
                for (ToolDefinition td : tools) {
                    var t = toolsArr.addObject();
                    t.put("type", "function");
                    var fn = t.putObject("function");
                    fn.put("name", td.name());
                    fn.put("description", td.description());
                    fn.set("parameters", td.inputSchema());
                }
            }
            return MAPPER.writeValueAsString(root);
        } catch (IOException e) {
            throw new BizException(ErrorCode.LLM_CALL_FAILED, "build request body failed");
        }
    }

    /** 把工具参数 JSON 串解析成 JsonNode；空/非法兜底为空对象 {}（§7.3 前置防御，避免序列化炸）。 */
    private JsonNode parseArgs(String json) {
        if (json == null || json.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(json);
        } catch (IOException e) {
            return MAPPER.createObjectNode();
        }
    }

    /** 解析 OpenAI HTTP 响应 -> LlmResponse。 */
    private LlmResponse parseResponse(Response response) throws IOException {
        ResponseBody body = response.body();
        if (body == null) {
            throw new BizException(ErrorCode.LLM_CALL_FAILED, "empty response body");
        }
        JsonNode root = MAPPER.readTree(body.string());
        return parseResponse(root, response.code());
    }

    /** 解析 OpenAI 响应 JSON（M8/T2 函数调用）：回填 content / finish_reason / toolCalls / usage。
     *  方法 public：与 buildRequestBody 同理，供 client 包单测直接构造 JsonNode 调用。 */
    public LlmResponse parseResponse(JsonNode root, int rawStatus) {
        JsonNode choice = root.path("choices").path(0);
        JsonNode message = choice.path("message");
        String content = message.path("content").asText(null);
        String finishReason = choice.path("finish_reason").asText(null);
        List<ToolCall> toolCalls = null;
        JsonNode tcArr = message.path("tool_calls");
        if (tcArr != null && tcArr.isArray() && tcArr.size() > 0) {
            toolCalls = new ArrayList<>();
            for (JsonNode tc : tcArr) {
                String id = tc.path("id").asText(null);
                String type = tc.path("type").asText(null);
                JsonNode fn = tc.path("function");
                String name = fn.path("name").asText(null);
                String args = fn.path("arguments").asText(null);
                toolCalls.add(new ToolCall(id, type, name, args));
            }
        }
        JsonNode usage = root.path("usage");
        Integer promptTokens = usage.has("prompt_tokens") ? usage.get("prompt_tokens").asInt() : null;
        Integer completionTokens = usage.has("completion_tokens") ? usage.get("completion_tokens").asInt() : null;
        return LlmResponse.builder()
                .content(content)
                .finishReason(finishReason)
                .toolCalls(toolCalls)
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .rawStatus(rawStatus)
                .build();
    }
}
