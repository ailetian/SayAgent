package com.sayagent.rbac;

import com.sayagent.rbac.dto.MeResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * M9/T4 菜单轴逻辑单测（无 Maven，走 JUnit Platform Launcher）。
 * 直接测 {@link MeServiceImpl}：用 Mockito 桩两个仓储，靠 {@code SecurityContextHolder}
 * 模拟不同角色登录（与 T2 {@code AuthContext} 判定一致），断言三种角色的菜单数。
 */
@ExtendWith(MockitoExtension.class)
class MeServiceImplTest {

    @Mock
    private RoleMenuRepository roleMenuRepository;
    @Mock
    private MenuItemRepository menuItemRepository;
    @Mock
    private ResourceAccessService resourceAccessService;
    @InjectMocks
    private MeServiceImpl meService;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @BeforeEach
    void stubResourceAccess() {
        // T5：/api/me 额外取资源授权 id 集合；默认返回空（无显式授权），不抛异常。
        // 用 lenient：admin / 未登录分支会短路、不调用本方法，strict stubbing 会报不必要桩，故放宽。
        lenient().when(resourceAccessService.visibleResourceIds(any(), any(), any())).thenReturn(Set.of());
    }

    private void loginAs(String... roles) {
        List<GrantedAuthority> authorities = Arrays.stream(roles)
                .map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r))
                .collect(Collectors.toList());
        Authentication auth = new UsernamePasswordAuthenticationToken("tester", "n/a", authorities);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /** 桩：角色→菜单编码用 expected，菜单编码→菜单视图逐项生成（保序）。 */
    private void stubRepos(String... expectedMenuCodes) {
        Set<String> codes = Arrays.stream(expectedMenuCodes)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        when(roleMenuRepository.findByRoleCodeIn(anySet())).thenAnswer(inv -> {
            // 忽略实际传入角色，直接返回期望的菜单编码映射行（贴近种子数据语义）
            return codes.stream().map(c -> {
                RoleMenu r = new RoleMenu();
                r.setMenuCode(c);
                return r;
            }).collect(Collectors.toList());
        });

        when(menuItemRepository.findByCodeInOrderBySortAsc(anySet())).thenAnswer((InvocationOnMock inv) -> {
            Set<String> asked = inv.getArgument(0);
            List<MenuItem> items = new ArrayList<>();
            int i = 0;
            for (String c : asked) {
                MenuItem m = new MenuItem();
                m.setCode(c);
                m.setName("name-" + c);
                m.setPath("/" + c);
                m.setIcon("icon-" + c);
                m.setSort(i++);
                items.add(m);
            }
            return items;
        });
    }

    private List<String> codesOf(MeResponse resp) {
        return resp.menus().stream().map(MeResponse.MenuVO::code).collect(Collectors.toList());
    }

    @Test
    void admin_gets_7_menus() {
        loginAs("ADMIN");
        stubRepos("chat", "agents", "knowledge", "models", "mcp", "users", "skills");
        MeResponse resp = meService.me();
        assertEquals("ADMIN", resp.role());
        assertTrue(resp.roles().contains("ADMIN"));
        assertEquals(7, resp.menus().size());
        // 映射正确性：title=name、route=path
        assertEquals("chat", resp.menus().get(0).code());
        assertEquals("name-chat", resp.menus().get(0).title());
        assertEquals("/chat", resp.menus().get(0).route());
        // T5：ADMIN 的资源 id 字段为 null（列表端点返回全量）
        assertNull(resp.accessibleKbIds());
        assertNull(resp.accessibleAgentIds());
    }

    @Test
    void operator_gets_4_menus_without_models_mcp_users() {
        loginAs("OPERATOR");
        stubRepos("chat", "agents", "knowledge", "skills");
        MeResponse resp = meService.me();
        assertEquals("OPERATOR", resp.role());
        assertEquals(4, resp.menus().size());
        List<String> codes = codesOf(resp);
        assertTrue(codes.contains("skills"));
        assertFalse(codes.contains("models"));
        assertFalse(codes.contains("mcp"));
        assertFalse(codes.contains("users"));
    }

    @Test
    void user_gets_3_menus_without_skills() {
        loginAs("USER");
        stubRepos("chat", "agents", "knowledge");
        MeResponse resp = meService.me();
        assertEquals("USER", resp.role());
        assertEquals(3, resp.menus().size());
        List<String> codes = codesOf(resp);
        assertFalse(codes.contains("skills"));
        assertFalse(codes.contains("users"));
        assertFalse(codes.contains("models"));
        assertFalse(codes.contains("mcp"));
        // T5：普通用户资源 id 字段非空（可能为空集合，取决于授权）
        assertNotNull(resp.accessibleKbIds());
        assertNotNull(resp.accessibleAgentIds());
    }

    @Test
    void unauthenticated_returns_empty_menus() {
        // 无认证：AuthContext.roleSet() 返回空集合，菜单为空、role 为 null
        MeResponse resp = meService.me();
        assertNull(resp.role());
        assertTrue(resp.roles().isEmpty());
        assertTrue(resp.menus().isEmpty());
    }
}
