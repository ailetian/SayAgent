package com.hify.hify.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.hify.common.exception.BizException;
import com.hify.hify.common.exception.ErrorCode;
import com.hify.hify.user.dto.LoginRequest;
import com.hify.hify.user.dto.LoginResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AuthController 端到端切片测试（§7.10 Mock 不连真库）。
 *
 * <p>用 {@code @SpringBootTest + @AutoConfigureMockMvc}（与 SecurityConfigTest 同款可靠模式）：
 * 因为 {@code AuthFilter} 是 {@code @Component} 过滤器，会被 {@code @WebMvcTest} 加载，
 * 但它依赖的 {@code JwtUtil} 由被排除的自动配置提供，导致 {@code @WebMvcTest} 上下文起不来。
 * 完整上下文里真实 SecurityConfig 把 {@code /api/auth/login} 设为 permitAll，请求免卡直达 Controller；
 * 401 映射由 {@code GlobalExceptionHandler}（{@code @RestControllerAdvice}）负责。
 *
 * <p>只验证 Controller 层契约：成功发 token 且响应无 password、错密码翻成 401。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerMockMvcTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private AuthService authService;
    @MockBean private AdminSeedRunner adminSeedRunner; // 阻止启动触发管理员播种（触库）

    @Test
    void testLogin_success_returns200AndNoPassword() throws Exception {
        when(authService.login(any(LoginRequest.class)))
                .thenReturn(new LoginResponse("eyJ.fake.token", "admin", "ADMIN"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("admin", "admin123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.token").exists())
                .andExpect(jsonPath("$.data.username").value("admin"))
                .andExpect(jsonPath("$.data.role").value("ADMIN"))
                .andExpect(jsonPath("$.data.password").doesNotExist()); // 响应绝不能含 password（§7.11）
    }

    @Test
    void testLogin_wrongPassword_returns401() throws Exception {
        when(authService.login(any(LoginRequest.class)))
                .thenThrow(new BizException(ErrorCode.AUTH_FAIL));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("admin", "wrong"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(4001)); // AUTH_FAIL 编号（§3.5 统一盒子）
    }
}
