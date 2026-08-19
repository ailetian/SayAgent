package com.sayagent.common.security;

import com.sayagent.common.exception.BizException;
import com.sayagent.common.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

/**
 * AuthContext 单测（M9/T2）。
 *
 * <p>覆盖：ADMIN/OPERATOR/USER 三种角色 + 多角色 + 无认证抛 UNAUTHORIZED + 空身份抛 UNAUTHORIZED，
 * 并以"复刻 KbAccessGuard 判定算法"的 oracle 断言 {@link AuthContext} 与其行为完全一致。
 */
@ExtendWith(MockitoExtension.class)
class AuthContextTest {

    @Mock Authentication auth;
    @Mock SecurityContext securityContext;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    /** 把给定 username + 角色（不含 ROLE_ 前缀）注入 SecurityContext，模拟一次已登录请求。 */
    private void withAuth(String username, String... roles) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (String role : roles) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        }
        when(securityContext.getAuthentication()).thenReturn(auth);
        when(auth.getName()).thenReturn(username);
        doReturn(authorities).when(auth).getAuthorities();
        SecurityContextHolder.setContext(securityContext);
    }

    @Test
    void admin_user_returns_expected() {
        withAuth("admin", "ADMIN");
        assertEquals("admin", AuthContext.currentUsername());
        assertTrue(AuthContext.isAdmin());
        assertEquals(Set.of("ADMIN"), AuthContext.roleSet());
    }

    @Test
    void operator_user_is_not_admin() {
        withAuth("op1", "OPERATOR");
        assertEquals("op1", AuthContext.currentUsername());
        assertFalse(AuthContext.isAdmin());
        assertEquals(Set.of("OPERATOR"), AuthContext.roleSet());
    }

    @Test
    void normal_user_is_not_admin() {
        withAuth("alice", "USER");
        assertEquals("alice", AuthContext.currentUsername());
        assertFalse(AuthContext.isAdmin());
        assertEquals(Set.of("USER"), AuthContext.roleSet());
    }

    @Test
    void multi_role_user_roleSet_contains_all() {
        withAuth("boss", "ADMIN", "OPERATOR", "USER");
        assertEquals("boss", AuthContext.currentUsername());
        assertTrue(AuthContext.isAdmin());
        assertEquals(Set.of("ADMIN", "OPERATOR", "USER"), AuthContext.roleSet());
    }

    @Test
    void no_auth_throws_unauthorized() {
        SecurityContextHolder.clearContext();
        BizException ex = assertThrows(BizException.class, AuthContext::currentUsername);
        assertEquals(ErrorCode.UNAUTHORIZED, ex.getErrorCode());
    }

    @Test
    void null_name_throws_unauthorized() {
        when(securityContext.getAuthentication()).thenReturn(auth);
        when(auth.getName()).thenReturn(null);
        SecurityContextHolder.setContext(securityContext);
        BizException ex = assertThrows(BizException.class, AuthContext::currentUsername);
        assertEquals(ErrorCode.UNAUTHORIZED, ex.getErrorCode());
    }

    @Test
    void behavior_parity_with_KbAccessGuard() {
        // 行为对齐 KbAccessGuard.isAdmin()/currentUser()：以 SecurityContextHolder 为唯一真相源，
        // admin = authorities 含 ROLE_ADMIN；username = auth.getName()；无认证抛 UNAUTHORIZED。
        // 用与 KbAccessGuard 完全相同的算法作为 oracle，断言 AuthContext 判定一致。
        withAuth("u1", "ADMIN");
        assertEquals(parityIsAdmin(), AuthContext.isAdmin());
        assertEquals(parityUsername("u1"), AuthContext.currentUsername());

        withAuth("u2", "USER");
        assertEquals(parityIsAdmin(), AuthContext.isAdmin());
        assertEquals(parityUsername("u2"), AuthContext.currentUsername());
    }

    // 复刻 KbAccessGuard 的判定算法（不依赖其 Spring bean / repository 依赖），仅作 oracle 对照。
    private boolean parityIsAdmin() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.getAuthorities().stream()
                .anyMatch(au -> "ROLE_ADMIN".equals(au.getAuthority()));
    }

    private String parityUsername(String fallback) {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || a.getName() == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return a.getName();
    }
}
