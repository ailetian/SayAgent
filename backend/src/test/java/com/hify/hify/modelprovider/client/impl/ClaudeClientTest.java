package com.hify.hify.modelprovider.client.impl;

import com.hify.hify.common.exception.BizException;
import com.hify.hify.common.exception.ErrorCode;
import com.hify.hify.modelprovider.client.ChatMessage;
import com.hify.hify.modelprovider.client.LlmResponse;
import com.hify.hify.modelprovider.client.ProviderConfig;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ClaudeClient 单测：mock OkHttp（§7.10 命名 test方法_场景_预期）。
 *
 * <p>验证：x-api-key 头、system 提到顶层、响应映射、非 2xx 抛 BizException。
 */
@ExtendWith(MockitoExtension.class)
class ClaudeClientTest {

    @Mock
    private OkHttpClient okHttpClient;

    private ClaudeClient client;

    @BeforeEach
    void setUp() {
        client = new ClaudeClient(okHttpClient, okHttpClient);
    }

    private void stub(int code, String body) throws IOException {
        Call call = mock(Call.class);
        Response response = new Response.Builder()
                .request(new Request.Builder().url("https://api.anthropic.com/v1/messages").build())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(code == 200 ? "OK" : "ERR")
                .body(ResponseBody.create(body, MediaType.parse("application/json")))
                .build();
        when(call.execute()).thenReturn(response);
        when(okHttpClient.newCall(any(Request.class))).thenReturn(call);
    }

    @Test
    void testSend_assemblesRequestAndParsesResponse() throws IOException {
        stub(200, "{\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],\"stop_reason\":\"end_turn\","
                + "\"usage\":{\"input_tokens\":5,\"output_tokens\":3}}");
        List<ChatMessage> messages = List.of(
                new ChatMessage("system", "you are helpful"),
                new ChatMessage("user", "hello"));
        ProviderConfig config = ProviderConfig.builder()
                .apiUrl("https://api.anthropic.com/v1").apiKey("sk-ant").model("claude-3-5-sonnet").build();

        LlmResponse r = client.send(messages, config);

        var captor = org.mockito.ArgumentCaptor.forClass(Request.class);
        verify(okHttpClient).newCall(captor.capture());
        Request req = captor.getValue();
        assertEquals("https://api.anthropic.com/v1/messages", req.url().toString());
        assertEquals("sk-ant", req.header("x-api-key"));
        assertEquals("2023-06-01", req.header("anthropic-version"));
        Buffer buf = new Buffer();
        req.body().writeTo(buf);
        String reqBody = buf.readUtf8();
        assertTrue(reqBody.contains("\"system\":\"you are helpful\""), reqBody);
        assertTrue(reqBody.contains("\"role\":\"user\""), reqBody);

        assertEquals("hi", r.getContent());
        assertEquals("end_turn", r.getFinishReason());
        assertEquals(5, r.getPromptTokens());
        assertEquals(3, r.getCompletionTokens());
        assertEquals(200, r.getRawStatus());
    }

    @Test
    void testSend_non2xx_throwsBizException() throws IOException {
        stub(500, "{\"error\":\"boom\"}");
        List<ChatMessage> messages = List.of(new ChatMessage("user", "hi"));
        ProviderConfig config = ProviderConfig.builder()
                .apiUrl("https://api.anthropic.com/v1").apiKey("k").model("claude-3-5-sonnet").build();

        BizException ex = assertThrows(BizException.class, () -> client.send(messages, config));
        assertEquals(ErrorCode.LLM_CALL_FAILED, ex.getErrorCode());
    }
}
