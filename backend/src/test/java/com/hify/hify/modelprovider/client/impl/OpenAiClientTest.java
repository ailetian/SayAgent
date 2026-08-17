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
 * OpenAiClient 单测：mock OkHttp（§7.10 命名 test方法_场景_预期）。
 *
 * <p>验证：请求 URL / Authorization 头 / 请求体拼装正确，响应能映射成 LlmResponse，
 * 以及非 2xx 时抛 {@code BizException(ErrorCode.LLM_CALL_FAILED)}。
 */
@ExtendWith(MockitoExtension.class)
class OpenAiClientTest {

    @Mock
    private OkHttpClient okHttpClient;

    private OpenAiClient client;

    @BeforeEach
    void setUp() {
        client = new OpenAiClient(okHttpClient, okHttpClient);
    }

    private void stub(int code, String body) throws IOException {
        Call call = mock(Call.class);
        Response response = new Response.Builder()
                .request(new Request.Builder().url("https://api.openai.com/v1/chat/completions").build())
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
        stub(200, "{\"choices\":[{\"message\":{\"content\":\"hi\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":3}}");
        List<ChatMessage> messages = List.of(new ChatMessage("user", "hello"));
        ProviderConfig config = ProviderConfig.builder()
                .apiUrl("https://api.openai.com/v1").apiKey("sk-test").model("gpt-4o").build();

        LlmResponse r = client.send(messages, config);

        var captor = org.mockito.ArgumentCaptor.forClass(Request.class);
        verify(okHttpClient).newCall(captor.capture());
        Request req = captor.getValue();
        assertEquals("https://api.openai.com/v1/chat/completions", req.url().toString());
        assertEquals("Bearer sk-test", req.header("Authorization"));
        Buffer buf = new Buffer();
        req.body().writeTo(buf);
        String reqBody = buf.readUtf8();
        assertTrue(reqBody.contains("\"model\":\"gpt-4o\""), reqBody);
        assertTrue(reqBody.contains("\"role\":\"user\""), reqBody);
        assertTrue(reqBody.contains("\"content\":\"hello\""), reqBody);

        assertEquals("hi", r.getContent());
        assertEquals("stop", r.getFinishReason());
        assertEquals(5, r.getPromptTokens());
        assertEquals(3, r.getCompletionTokens());
        assertEquals(200, r.getRawStatus());
    }

    @Test
    void testSend_non2xx_throwsBizException() throws IOException {
        stub(500, "{\"error\":\"boom\"}");
        List<ChatMessage> messages = List.of(new ChatMessage("user", "hi"));
        ProviderConfig config = ProviderConfig.builder()
                .apiUrl("https://api.openai.com/v1").apiKey("k").model("gpt-4o").build();

        BizException ex = assertThrows(BizException.class, () -> client.send(messages, config));
        assertEquals(ErrorCode.LLM_CALL_FAILED, ex.getErrorCode());
    }
}
