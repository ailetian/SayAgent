package com.hify.hify.modelprovider.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hify.hify.common.tool.ToolCall;
import com.hify.hify.common.tool.ToolDefinition;
import com.hify.hify.modelprovider.client.impl.OpenAiClient;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M8/T2 OpenAiClient 非流式函数调用单测。
 *
 * <p>不依赖真实 HTTP：直接调 {@code buildRequestBody} / {@code parseResponse(JsonNode,int)}，
 * 构造 JsonNode 验证「请求含 tools 数组」与「响应回填 toolCalls / finish_reason」。
 */
class OpenAiClientFunctionCallTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final OpenAiClient client = new OpenAiClient(new OkHttpClient(), new OkHttpClient());

    private ProviderConfig config() {
        return ProviderConfig.builder()
                .apiUrl("https://api.openai.com/v1")
                .apiKey("test-key")
                .model("gpt-4o")
                .build();
    }

    private ToolDefinition currentToolDef() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties");
        return new ToolDefinition("current-time", "返回当前的日期与时间", schema);
    }

    @Test
    void buildRequestBody_containsToolsArray_withNameDescriptionParameters() throws Exception {
        List<ChatMessage> messages = List.of(new ChatMessage("user", "现在几点了？"));
        String body = client.buildRequestBody(messages, config(), false, List.of(currentToolDef()));

        JsonNode root = mapper.readTree(body);
        assertTrue(root.path("tools").isArray(), "请求体应含 tools 数组");
        JsonNode fn = root.path("tools").get(0).path("function");
        assertEquals("current-time", fn.path("name").asText());
        assertEquals("返回当前的日期与时间", fn.path("description").asText());
        assertTrue(fn.path("parameters").has("type"), "parameters 应含 type 字段");
    }

    @Test
    void buildRequestBody_serializesAssistantToolCalls_andToolMessage() throws Exception {
        ToolCall call = new ToolCall("call_1", "function", "current-time", "{}");
        ChatMessage assistant = new ChatMessage("assistant", null);
        assistant.setToolCalls(List.of(call));
        ChatMessage toolMsg = new ChatMessage("tool", "2026-08-07T10:00:00+08:00");
        toolMsg.setToolCallId("call_1");

        String body = client.buildRequestBody(List.of(assistant, toolMsg), config(), false, List.of());
        JsonNode root = mapper.readTree(body);
        ArrayNode messages = (ArrayNode) root.path("messages");
        assertEquals(2, messages.size());
        assertTrue(messages.get(0).path("tool_calls").isArray(), "assistant 消息应含 tool_calls");
        assertEquals("call_1", messages.get(1).path("tool_call_id").asText(), "tool 消息应带回 tool_call_id");
    }

    @Test
    void parseResponse_fillsToolCalls_withNameAndArguments() throws Exception {
        ObjectNode root = mapper.createObjectNode();
        ArrayNode choices = root.putArray("choices");
        ObjectNode choice = choices.addObject();
        ObjectNode msg = choice.putObject("message");
        msg.putNull("content");
        choice.put("finish_reason", "tool_calls");
        ArrayNode toolCalls = msg.putArray("tool_calls");
        ObjectNode tc = toolCalls.addObject();
        tc.put("id", "call_1");
        tc.put("type", "function");
        ObjectNode fn = tc.putObject("function");
        fn.put("name", "current-time");
        fn.put("arguments", "{\"tz\":\"Asia/Shanghai\"}");

        LlmResponse resp = client.parseResponse(root, 200);
        assertNotNull(resp.getToolCalls(), "toolCalls 不应为 null");
        assertEquals(1, resp.getToolCalls().size());
        ToolCall parsed = resp.getToolCalls().get(0);
        assertEquals("current-time", parsed.functionName());
        assertEquals("{\"tz\":\"Asia/Shanghai\"}", parsed.argumentsJson());
        assertEquals("call_1", parsed.id());
        assertEquals("tool_calls", resp.getFinishReason());
        assertNull(resp.getContent(), "无正文的工具调用响应 content 应为 null");
    }

    @Test
    void parseResponse_withoutToolCalls_keepsToolCallsNull() throws Exception {
        ObjectNode root = mapper.createObjectNode();
        ArrayNode choices = root.putArray("choices");
        ObjectNode choice = choices.addObject();
        ObjectNode msg = choice.putObject("message");
        msg.put("content", "现在 10 点");
        choice.put("finish_reason", "stop");

        LlmResponse resp = client.parseResponse(root, 200);
        assertNull(resp.getToolCalls(), "普通回复 toolCalls 应为 null");
        assertEquals("stop", resp.getFinishReason());
        assertEquals("现在 10 点", resp.getContent());
    }
}
