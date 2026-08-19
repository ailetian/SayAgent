package com.sayagent.common.security;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuthFilterTest {

    private final JwtUtil jwtUtil = new JwtUtil("test-secret-key-must-be-at-least-32-bytes-long!!", 7200000L);
    private final AuthFilter filter = new AuthFilter(jwtUtil);

    @Test
    void testDoFilter_noToken_passesThrough() throws Exception {
        // 无 token：闸机放行（不拦），授权判定交给 SecurityConfig 的 authorizeHttpRequests
        // （登录等 permitAll 路径借此可达；受保护路径由 Spring Security 返 401）
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        assertEquals(HttpServletResponse.SC_OK, res.getStatus());
    }

    @Test
    void testDoFilter_validToken_passes() throws Exception {
        String token = jwtUtil.sign("alice", "USER");
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        assertEquals(HttpServletResponse.SC_OK, res.getStatus());
    }

    @Test
    void testDoFilter_fakeToken_returns401() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer not-a-real-token");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, res.getStatus());
    }
}
