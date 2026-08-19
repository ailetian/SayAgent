package com.sayagent.conversation.web;

import com.sayagent.common.security.JwtUtil;
import com.sayagent.common.security.SecurityConfig;
import com.sayagent.conversation.dto.ChatHistoryPage;
import com.sayagent.conversation.service.ConversationService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T2 验收（控制层切片）：未登录访问所有 /api/chat/** 端点 → 401（§7.11 鉴权在过滤链与服务层）；
 * 带合法 JWT 时 Controller 仅做薄包装并把请求委托给 ConversationService。
 */
@WebMvcTest(controllers = ConversationController.class)
@AutoConfigureMockMvc
@Import({SecurityConfig.class, JwtUtil.class})
@TestPropertySource(properties = {
        "hify.jwt.secret=test-secret-key-at-least-256-bits-long-for-hmac-sha256",
        "hify.jwt.expiration-ms=7200000"
})
class ConversationControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    @Autowired
    private JwtUtil jwtUtil;

    @MockBean
    private ConversationService conversationService;
    @MockBean
    private PasswordEncoder passwordEncoder;

    @Test
    void testStream_noLogin_returns401() throws Exception {
        mockMvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("message", "hi"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testListConversations_noLogin_returns401() throws Exception {
        mockMvc.perform(get("/api/chat")).andExpect(status().isUnauthorized());
    }

    @Test
    void testListMessages_noLogin_returns401() throws Exception {
        mockMvc.perform(get("/api/chat/c-1/messages")).andExpect(status().isUnauthorized());
    }

    @Test
    void testDeleteConversation_noLogin_returns401() throws Exception {
        mockMvc.perform(delete("/api/chat/c-1")).andExpect(status().isUnauthorized());
    }

    @Test
    void testListConversations_withValidToken_delegatesToService() throws Exception {
        String token = jwtUtil.sign("tester", "USER");
        when(conversationService.listConversations(anyString())).thenReturn(List.of());
        mockMvc.perform(get("/api/chat").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        verify(conversationService).listConversations("tester");
    }

    @Test
    void testListMessages_withValidToken_delegatesToService() throws Exception {
        String token = jwtUtil.sign("tester", "USER");
        when(conversationService.listMessages(anyString(), anyString(), any(), anyInt()))
                .thenReturn(new ChatHistoryPage(List.of(), null, false));
        mockMvc.perform(get("/api/chat/c-1/messages").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        verify(conversationService).listMessages("c-1", "tester", null, 20);
    }

    @Test
    void testDeleteConversation_withValidToken_delegatesToService() throws Exception {
        String token = jwtUtil.sign("tester", "USER");
        mockMvc.perform(delete("/api/chat/c-1").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        verify(conversationService).deleteConversation("c-1", "tester");
    }
}
