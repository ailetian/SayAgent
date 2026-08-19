package com.sayagent.user;

import com.sayagent.common.exception.BizException;
import com.sayagent.common.security.JwtUtil;
import com.sayagent.user.dto.LoginRequest;
import com.sayagent.user.dto.LoginResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * AuthService 单元点检（§7.10 纯 Mock、不连真库）。
 *
 * <p>大白话：只测「后台核对员工」的核心逻辑——
 * <ul>
 *   <li>账号密码对得上 → 发 JWT；</li>
 *   <li>密码对不上 / 账号不存在 → 抛 {@code BizException(AUTH_FAIL)}（最终翻成 HTTP 401）。</li>
 * </ul>
 *
 * <p>依赖全部用 Mockito 替身：{@code UserRepository}/{@code PasswordEncoder}/{@code JwtUtil} 都不碰真库、真加密、真签名。
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private CustomUserDetailsService userDetailsService;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtUtil jwtUtil;
    @InjectMocks private AuthServiceImpl authService;

    @Test
    void testLogin_correctCredential_returnsToken() {
        User user = new User();
        user.setUsername("admin");
        user.setPassword("$2a$xxxx");
        user.setRole(UserRole.ADMIN);
        when(userRepository.findByUsernameAndDeletedFalse("admin")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("admin123", "$2a$xxxx")).thenReturn(true);
        when(jwtUtil.sign("admin", "ADMIN")).thenReturn("eyJ.fake.token");

        LoginResponse resp = authService.login(new LoginRequest("admin", "admin123"));
        assertTrue(resp.token().startsWith("eyJ"), "应返回 JWT");
        assertTrue("ADMIN".equals(resp.role()), "角色应为 ADMIN");
    }

    @Test
    void testLogin_wrongPassword_throwsBizException() {
        User user = new User();
        user.setUsername("admin");
        user.setPassword("$2a$xxxx");
        user.setRole(UserRole.ADMIN);
        when(userRepository.findByUsernameAndDeletedFalse("admin")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "$2a$xxxx")).thenReturn(false);

        assertThrows(BizException.class,
                () -> authService.login(new LoginRequest("admin", "wrong")));
    }

    @Test
    void testLogin_unknownUser_throwsBizException() {
        when(userRepository.findByUsernameAndDeletedFalse("ghost")).thenReturn(Optional.empty());
        assertThrows(BizException.class,
                () -> authService.login(new LoginRequest("ghost", "x")));
    }
}
