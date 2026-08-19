package com.sayagent.modelprovider.client.impl;

import com.sayagent.common.exception.BizException;
import com.sayagent.common.exception.ErrorCode;
import com.sayagent.modelprovider.client.ChatMessage;
import com.sayagent.modelprovider.client.LlmResponse;
import com.sayagent.modelprovider.client.ProviderConfig;
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
 * GeminiClient 单测：mock OkHttp（§7.10 命名 test方法_场景_预期）。
 *
 * <p>验证：key 走 query 参数、model 进路径、assistant 翻成 model、响应映射、非 2xx 抛 BizException。
 */
@ExtendWith(MockitoExtension.class)
class GeminiClientTest {

    @Mock
    private OkHttpClient okHttpClient;

    private GeminiClient client;

    @BeforeEach
    void setUp() {
        client = new GeminiClient(okHttpClient, okHttpClient);
    }

    private void stub(int code, String body) throws IOException {
        Call call = mock(Call.class);
        Response response = new Response.Builder()
                .request(new Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/gemini:generateContent").build())
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
        stub(200, "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hi\"}]},\"finishReason\":\"STOP\"}],"
                + "\"usageMetadata\":{\"promptTokenCount\":5,\"candidatesTokenCount\":3}}");
        List<ChatMessage> messages = List.of(new ChatMessage("user", "hello"));
        ProviderConfig config = ProviderConfig.builder()
                .apiUrl("https://generativelanguage.googleapis.com/v1beta").apiKey("gem-key").model("gemini-1.5-pro").build();

        LlmResponse r = client.send(messages, config);

        var captor = org.mockito.ArgumentCaptor.forClass(Request.class);
        verify(okHttpClient).newCall(captor.capture());
        Request req = captor.getValue();
        String url = req.url().toString();
        assertTrue(url.contains("/models/gemini-1.5-pro:generateContent"), url);
        assertTrue(url.contains("key=gem-key"), url);
        Buffer buf = new Buffer();
        req.body().writeTo(buf);
        String reqBody = buf.readUtf8();
        assertTrue(reqBody.contains("\"role\":\"user\""), reqBody);

        assertEquals("hi", r.getContent());
        assertEquals("STOP", r.getFinishReason());
        assertEquals(5, r.getPromptTokens());
        assertEquals(3, r.getCompletionTokens());
        assertEquals(200, r.getRawStatus());
    }

    @Test
    void testSend_assistantRoleMappedToModel() throws IOException {
        stub(200, "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hi\"}]}}]}");
        List<ChatMessage> messages = List.of(new ChatMessage("assistant", "prev"));
        ProviderConfig config = ProviderConfig.builder()
                .apiUrl("https://generativelanguage.googleapis.com/v1beta").apiKey("gem-key").model("gemini-1.5-pro").build();

        client.send(messages, config);

        var captor = org.mockito.ArgumentCaptor.forClass(Request.class);
        verify(okHttpClient).newCall(captor.capture());
        Buffer buf = new Buffer();
        captor.getValue().body().writeTo(buf);
        assertTrue(buf.readUtf8().contains("\"role\":\"model\""));
    }

    @Test
    void testSend_non2xx_throwsBizException() throws IOException {
        stub(500, "{\"error\":\"boom\"}");
        List<ChatMessage> messages = List.of(new ChatMessage("user", "hi"));
        ProviderConfig config = ProviderConfig.builder()
                .apiUrl("https://generativelanguage.googleapis.com/v1beta").apiKey("k").model("gemini-1.5-pro").build();

        BizException ex = assertThrows(BizException.class, () -> client.send(messages, config));
        assertEquals(ErrorCode.LLM_CALL_FAILED, ex.getErrorCode());
    }
}
