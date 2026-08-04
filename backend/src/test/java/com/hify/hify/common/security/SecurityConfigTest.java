package com.hify.hify.common.security;

import com.hify.hify.user.AdminSeedRunner;
import com.hify.hify.user.AuthService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 门禁制度集成测试（验证 SecurityConfig 的"登录免卡、其余要卡"）。
 *
 * <p>说明：原拟用 @WebMvcTest 切片测试，但本项目的 @WebMvcTest 不会把自定义 SecurityFilterChain 可靠地
 * 接进 MockMvc（springSecurityFilterChain 以 FilterRegistrationBean 注册，@AutoConfigureMockMvc 在切片下
 * 取不到），导致受保护路径没被拦成 401。故改用 @SpringBootTest + @AutoConfigureMockMvc：会正确把
 * springSecurityFilterChain 注册进 MockMvc，行为验证最可靠。
 *
 * <p>隔离策略：T5 起 {@code /api/auth/login} 已有真实 {@code AuthController} 接管，且启动时
 * {@link AdminSeedRunner} 会播种 admin（触库）。为让本测试免 DB、稳定验证"门禁"语义，把
 * {@link AuthService} 与 {@link AdminSeedRunner} 都替成 mock：登录请求由 mock 直接回一张 stub 凭证，
 * 从而只验证"门禁是否放行登录路径"，不关心登录业务本身。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    // 隔离登录业务与播种器，避免测试触库；login 行为由下方 stub 控制。
    @MockBean
    private AuthService authService;

    @MockBean
    private AdminSeedRunner adminSeedRunner;

    @Test
    void testProtectedPath_noToken_returns401() throws Exception {
        // /api/agents 无 controller，且未带 token → 闸机放行后 authorizeHttpRequests 拦截 → 401
        mockMvc.perform(get("/api/agents"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testLoginPath_noToken_notBlockedByFilter() throws Exception {
        // 登录路径 permitAll：闸机对无 token 放行，SecurityConfig 免卡 → 请求抵达 AuthController。
        // 用 mock 让 AuthService 直接回一张 stub 凭证，证明登录接口没被门禁拦下（非 401 即过关）。
        when(authService.login(any(LoginRequest.class)))
                .thenReturn(new LoginResponse("stub-token", "admin", "ADMIN"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().is2xxSuccessful()); // 401/403 才算失败
    }
}
