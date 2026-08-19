package com.sayagent.knowledge.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sayagent.common.security.JwtUtil;
import com.sayagent.common.security.SecurityConfig;
import com.sayagent.knowledge.service.KbAdminService;
import com.sayagent.knowledge.service.KnowledgeService;
import com.sayagent.knowledge.service.MountService;
import com.sayagent.knowledge.web.DocumentVO;
import com.sayagent.knowledge.web.KnowledgeBaseCreateRequest;
import com.sayagent.knowledge.service.IndexingJobService;
import com.sayagent.knowledge.service.KbQaService;
import com.sayagent.knowledge.web.KnowledgeBaseVO;

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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * KnowledgeController 鉴权 + 主流程测试（M5 T6 整改：改用 @WebMvcTest 切片，不拉整库上下文）。
 *
 * <p>大白话：验证「未登录调 /api/knowledge/** 必须 401」以及「带有效 JWT 后能正常上传/检索」。
 *
 * <p>为什么用 @WebMvcTest 而非 @SpringBootTest（§7.10 规则35）：本测试只需 Web + Security 过滤链，
 * 根本不需要 DataSource / Flyway / PG。@WebMvcTest 切片只加载 Controller + SecurityConfig + AuthFilter，
 * headless 无真实库也能直接 green，避免把整库上下文耦合进单测（坑位 11）。
 *
 * <p>依赖处理：
 * - {@code SecurityConfig} 与 {@code JwtUtil} 用 @Import 注入真实 bean（JwtUtil 仅做 HMAC 签名，无 DB 依赖）；
 * - {@code AuthFilter} 是 {@code @Component} 的 {@code Filter}，被 @WebMvcTest 切片自动加载；
 * - {@code KnowledgeService} 用 @MockBean 隔离业务；{@code PasswordEncoder} 用 @MockBean（SecurityConfig 仅持有，本测不用）。
 */
@WebMvcTest(controllers = KnowledgeController.class)
@AutoConfigureMockMvc
@Import({SecurityConfig.class, JwtUtil.class})
@TestPropertySource(properties = {
        "sayagent.jwt.secret=test-secret-key-at-least-256-bits-long-for-hmac-sha256",
        "sayagent.jwt.expiration-ms=7200000"
})
class KnowledgeControllerAuthTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JwtUtil jwtUtil;

    @MockBean
    private KnowledgeService knowledgeService;
    @MockBean
    private KbAdminService kbAdminService;
    @MockBean
    private MountService mountService;
    @MockBean
    private KbQaService kbQaService;
    @MockBean
    private IndexingJobService indexingJobService;
    @MockBean
    private PasswordEncoder passwordEncoder;

    @Test
    void testController_noLogin_returns401() throws Exception {
        mockMvc.perform(post("/api/knowledge/upload")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testController_withValidToken_upload_returnsDocumentVO() throws Exception {
        String token = jwtUtil.sign("tester", "USER");
        when(knowledgeService.uploadDocument(any())).thenReturn("doc-1");
        when(knowledgeService.getDocumentVO("doc-1")).thenReturn(new DocumentVO("doc-1", "INDEXED", 2));

        mockMvc.perform(post("/api/knowledge/upload")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "kbId", 1, "type", "TEXT", "title", "t", "content", "c"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.docId").value("doc-1"))
                .andExpect(jsonPath("$.data.status").value("INDEXED"));
    }

    @Test
    void testController_withValidToken_retrieve_returnsChunkVOList() throws Exception {
        String token = jwtUtil.sign("tester", "USER");
        when(knowledgeService.retrieve(any(), any(), anyInt())).thenReturn(List.of());

        mockMvc.perform(post("/api/knowledge/retrieve")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "kbId", 1, "query", "什么是 hify", "topK", 3))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void testController_noLogin_createBase_returns401() throws Exception {
        mockMvc.perform(post("/api/knowledge/bases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"kb\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testController_withValidToken_createBase_returnsVO() throws Exception {
        String token = jwtUtil.sign("tester", "USER");
        when(kbAdminService.createBase(any())).thenReturn(
                new KnowledgeBaseVO(1L, "kb", "", null, 1024, null, "tester",
                        com.sayagent.knowledge.entity.KnowledgeBase.ChunkStrategy.AUTO,
                        "zh-CN", com.sayagent.knowledge.entity.KnowledgeBase.Status.ACTIVE, true, null));

        mockMvc.perform(post("/api/knowledge/bases")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "kb"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.name").value("kb"));
    }
}
