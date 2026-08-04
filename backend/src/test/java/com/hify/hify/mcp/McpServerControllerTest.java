package com.hify.hify.mcp;

import com.hify.hify.mcp.dto.McpServerCreateReq;
import com.hify.hify.mcp.dto.McpServerVO;
import com.hify.hify.mcp.McpServerController;
import com.hify.hify.mcp.McpServerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * McpServerController HTTP 边界冒烟（M7/T1，§3.4 分层纪律 + §3.5 响应契约）。
 *
 * <p>大白话：只验「门面层」两件事——① POST 新增 / GET 列表都返回 {@code Result{code:0,data:...}}（统一响应体）；
 * ② 入参校验由 @Valid 触发（缺 name 等返回 400）。控制器极薄，只把请求转给 service 再装盒子，
 * 真正的 ADMIN 校验在服务层（见 {@link McpServerServiceAuthTest}），401/403 闸门由 SecurityConfig 兜底。
 *
 * <p>用 standaloneSetup 隔离装配（不拉 DB / Security 全上下文，坑位11），手动注入 mock service。
 * 命名 {@code test方法_场景_预期}（§7.10 规则34）。
 */
@ExtendWith(MockitoExtension.class)
class McpServerControllerTest {

    @Mock
    private McpServerService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new McpServerController(service)).build();
    }

    @Test
    @DisplayName("createServer：合法入参返回 Result{code:0}")
    void createServer_validBody_returnsResultOk() throws Exception {
        when(service.createServer(any(McpServerCreateReq.class)))
                .thenReturn(new McpServerVO(1L, "订单系统", "http://order.internal:8080/mcp", "SSE", 1, null, null));

        mockMvc.perform(post("/api/mcp/servers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"订单系统\",\"address\":\"http://order.internal:8080/mcp\",\"type\":\"SSE\",\"status\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.name").value("订单系统"));
    }

    @Test
    @DisplayName("listServers：返回 Result{code:0, data:[]}")
    void listServers_returnsResultOk() throws Exception {
        when(service.listServers()).thenReturn(List.of(
                new McpServerVO(1L, "订单系统", "http://order.internal:8080/mcp", "SSE", 1, null, null)));

        mockMvc.perform(get("/api/mcp/servers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].name").value("订单系统"));
    }

    @Test
    @DisplayName("createServer：缺 name 触发 @Valid 返回 400")
    void createServer_missingName_returns400() throws Exception {
        mockMvc.perform(post("/api/mcp/servers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"address\":\"http://x:8080/mcp\",\"type\":\"SSE\",\"status\":1}"))
                .andExpect(status().isBadRequest());
    }
}
