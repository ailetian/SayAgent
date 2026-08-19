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
 * OllamaClient 单测：mock OkHttp（§7.10 命名 test方法_场景_预期）。
 *
 * <p>验证：/api/chat 路径、stream=false、响应映射、非 2xx 抛 BizException。
 */
@ExtendWith(MockitoExtension.class)
class OllamaClientTest {

    @Mock
    private OkHttpClient okHttpClient;

    private OllamaClient client;

    @BeforeEach
    void setUp() {
        client = new OllamaClient(okHttpClient, okHttpClient);
    }

    private void stub(int code, String body) throws IOException {
        Call call = mock(Call.class);
        Response response = new Response.Builder()
                .request(new Request.Builder().url("http://localhost:11434/api/chat").build())
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
        stub(200, "{\"message\":{\"role\":\"assistant\",\"content\":\"hi\"},\"done\":true,"
                + "\"prompt_eval_count\":5,\"eval_count\":3}");
        List<ChatMessage> messages = List.of(new ChatMessage("user", "hello"));
        ProviderConfig config = ProviderConfig.builder()
                .apiUrl("http://localhost:11434").apiKey("").model("llama3").build();

        LlmResponse r = client.send(messages, config);

        var captor = org.mockito.ArgumentCaptor.forClass(Request.class);
        verify(okHttpClient).newCall(captor.capture());
        Request req = captor.getValue();
        assertEquals("http://localhost:11434/api/chat", req.url().toString());
        Buffer buf = new Buffer();
        req.body().writeTo(buf);
        String reqBody = buf.readUtf8();
        assertTrue(reqBody.contains("\"model\":\"llama3\""), reqBody);
        assertTrue(reqBody.contains("\"stream\":false"), reqBody);

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
                .apiUrl("http://localhost:11434").apiKey("").model("llama3").build();

        BizException ex = assertThrows(BizException.class, () -> client.send(messages, config));
        assertEquals(ErrorCode.LLM_CALL_FAILED, ex.getErrorCode());
    }
}
