package com.sayagent.rbac;

import com.sayagent.common.exception.BizException;
import com.sayagent.common.exception.ErrorCode;
import com.sayagent.rbac.ResourceAccessAuditRepository;
import com.sayagent.rbac.dto.ResourceAccessView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M9/T5 资源授权服务单测（无 Maven，走 JUnit Platform Launcher）。
 * 直接测 {@link ResourceAccessService}：Mockito 桩 {@link ResourceAccessRepository}，
 * 不连真库（§7.10 规则35）。覆盖：创建者自授权写入/幂等、ADMIN 返回 null、USER 返回授权 id 集合。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ResourceAccessServiceTest {

    @Mock
    private ResourceAccessRepository resourceAccessRepository;
    @Mock
    private ResourceAccessAuditRepository auditRepository;
    @InjectMocks
    private ResourceAccessService service;

    @Test
    void grantCreator_writesUserRowWithFullPerms() {
        // 未存在 → 写入 (USER, 创建者, KB, id, 全1)
        when(resourceAccessRepository
                .findByPrincipalTypeAndPrincipalIdAndResourceTypeAndResourceId(
                        eq("USER"), eq("alice"), eq("KB"), eq(10L)))
                .thenReturn(Optional.empty());

        service.grantCreator("KB", 10L, "alice");

        ArgumentCaptor<ResourceAccess> captor = ArgumentCaptor.forClass(ResourceAccess.class);
        verify(resourceAccessRepository).save(captor.capture());
        ResourceAccess saved = captor.getValue();
        assertEquals("USER", saved.getPrincipalType());
        assertEquals("alice", saved.getPrincipalId());
        assertEquals("KB", saved.getResourceType());
        assertEquals(10L, saved.getResourceId());
        assertTrue(saved.getCanRead());
        assertTrue(saved.getCanWrite());
        assertTrue(saved.getCanUse());
        assertTrue(saved.getCanEdit());
    }

    @Test
    void grantCreator_idempotentSkipsWhenExists() {
        // 已存在 → 不重复写入（避免唯一键冲突 uk_principal_resource）
        when(resourceAccessRepository
                .findByPrincipalTypeAndPrincipalIdAndResourceTypeAndResourceId(
                        eq("USER"), eq("alice"), eq("KB"), eq(10L)))
                .thenReturn(Optional.of(new ResourceAccess("USER", "alice", "KB", 10L)));

        service.grantCreator("KB", 10L, "alice");

        verify(resourceAccessRepository, never()).save(any());
    }

    @Test
    void grantCreator_skipsWhenCreatorBlank() {
        // 无创建者（历史脏数据）不写授权行，由 visibility=PUBLIC 兜底
        service.grantCreator("KB", 10L, null);
        service.grantCreator("KB", 10L, "   ");
        verify(resourceAccessRepository, never()).save(any());
    }

    @Test
    void visibleResourceIds_adminReturnsNull() {
        // ADMIN → null（语义：可见全部）
        Set<Long> result = service.visibleResourceIds("admin", Set.of("ADMIN"), "KB");
        assertNull(result);
        // ADMIN 不查库
        verify(resourceAccessRepository, never())
                .findByPrincipalTypeAndPrincipalIdAndResourceTypeAndCanReadTrue(any(), any(), any());
    }

    @Test
    void visibleResourceIds_userReturnsGrantedIds() {
        ResourceAccess r1 = new ResourceAccess("USER", "bob", "KB", 5L);
        ResourceAccess r2 = new ResourceAccess("USER", "bob", "KB", 7L);
        when(resourceAccessRepository
                .findByPrincipalTypeAndPrincipalIdAndResourceTypeAndCanReadTrue(
                        eq("USER"), eq("bob"), eq("KB")))
                .thenReturn(List.of(r1, r2));

        Set<Long> result = service.visibleResourceIds("bob", Set.of("USER"), "KB");

        assertFalse(result.contains(null));
        assertTrue(result.contains(5L));
        assertTrue(result.contains(7L));
        assertEquals(2, result.size());
    }

    @Test
    void visibleResourceIds_includesRoleBasedGrants() {
        // 个人授权 5L + 角色(USER)授权 9L，应并集返回（修复前 ROLE 授权被忽略，导致「有授权却看不到/用不了」）
        ResourceAccess personal = new ResourceAccess("USER", "bob", "KB", 5L);
        ResourceAccess roleGrant = new ResourceAccess("ROLE", "USER", "KB", 9L);
        when(resourceAccessRepository
                .findByPrincipalTypeAndPrincipalIdAndResourceTypeAndCanReadTrue(
                        eq("USER"), eq("bob"), eq("KB")))
                .thenReturn(List.of(personal));
        when(resourceAccessRepository
                .findByPrincipalTypeAndPrincipalIdInAndResourceTypeAndCanReadTrue(
                        eq("ROLE"), eq(Set.of("USER")), eq("KB")))
                .thenReturn(List.of(roleGrant));

        Set<Long> result = service.visibleResourceIds("bob", Set.of("USER"), "KB");

        assertTrue(result.contains(5L));
        assertTrue(result.contains(9L));
        assertEquals(2, result.size());
    }

    // ===== M9/T7 新增：授权管理守卫 + 授权/撤销/列出 =====

    @Mock Authentication auth;
    @Mock SecurityContext securityContext;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** 把已登录身份注入 SecurityContext（与 AuthContextTest 同构）。 */
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
    void requireManager_allowsAdminWithoutRepoLookup() {
        withAuth("admin", "ADMIN");
        // ADMIN 直接放行，不查库
        service.requireManager("KB", 10L);
        verify(resourceAccessRepository, never())
                .findByPrincipalTypeAndPrincipalIdAndResourceTypeAndResourceId(any(), any(), any(), any());
    }

    @Test
    void requireManager_allowsCreatorWithCanEditRow() {
        withAuth("alice", "USER");
        ResourceAccess creatorRow = new ResourceAccess("USER", "alice", "KB", 10L); // 全权行（T5 grantCreator 写入）
        when(resourceAccessRepository
                .findByPrincipalTypeAndPrincipalIdAndResourceTypeAndResourceId(
                        eq("USER"), eq("alice"), eq("KB"), eq(10L)))
                .thenReturn(Optional.of(creatorRow));

        service.requireManager("KB", 10L); // 不抛异常
    }

    @Test
    void requireManager_throwsForbiddenForPlainUserWithoutCanEdit() {
        withAuth("bob", "USER");
        // 无授权行（bob 既非创建者也无 can_edit）
        when(resourceAccessRepository
                .findByPrincipalTypeAndPrincipalIdAndResourceTypeAndResourceId(
                        eq("USER"), eq("bob"), eq("KB"), eq(10L)))
                .thenReturn(Optional.empty());

        BizException ex = assertThrows(BizException.class, () -> service.requireManager("KB", 10L));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
    }

    @Test
    void requireManager_throwsForbiddenForReadOnlyGrantee() {
        withAuth("bob", "USER");
        // 仅有 can_read（无 can_edit），不能管理
        ResourceAccess readOnly = new ResourceAccess("USER", "bob", "KB", 10L);
        readOnly.setCanEdit(false);
        when(resourceAccessRepository
                .findByPrincipalTypeAndPrincipalIdAndResourceTypeAndResourceId(
                        eq("USER"), eq("bob"), eq("KB"), eq(10L)))
                .thenReturn(Optional.of(readOnly));

        BizException ex = assertThrows(BizException.class, () -> service.requireManager("KB", 10L));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
    }

    @Test
    void grant_updatesExistingRowIdempotently() {
        // 已存在行 → 更新同一条（不新建），不破坏唯一键
        ResourceAccess existing = new ResourceAccess("USER", "bob", "KB", 10L);
        when(resourceAccessRepository
                .findByPrincipalTypeAndPrincipalIdAndResourceTypeAndResourceId(
                        eq("USER"), eq("bob"), eq("KB"), eq(10L)))
                .thenReturn(Optional.of(existing));

        withAuth("admin", "ADMIN"); // grant 内部 writeAudit 需合法身份
        service.grant("USER", "bob", "KB", 10L, true, false, false, false);

        ArgumentCaptor<ResourceAccess> captor = ArgumentCaptor.forClass(ResourceAccess.class);
        verify(resourceAccessRepository).save(captor.capture());
        ResourceAccess saved = captor.getValue();
        assertEquals(existing, saved); // 更新的是同一实体
        assertTrue(saved.getCanRead());
        assertFalse(saved.getCanEdit()); // 按请求把 can_edit 置 false
    }

    @Test
    void listGrants_mapsFieldsToView() {
        ResourceAccess r1 = new ResourceAccess("USER", "alice", "KB", 10L); // 全权
        ResourceAccess r2 = new ResourceAccess("ROLE", "OPERATOR", "KB", 10L);
        r2.setCanRead(false);
        r2.setCanWrite(true);
        r2.setCanEdit(true);
        when(resourceAccessRepository.findByResourceTypeAndResourceId(eq("KB"), eq(10L)))
                .thenReturn(List.of(r1, r2));

        List<ResourceAccessView> views = service.listGrants("KB", 10L);

        assertEquals(2, views.size());
        assertTrue(views.get(0).isCanRead() && views.get(0).isCanEdit());
        assertEquals("alice", views.get(0).getPrincipalId());
        assertEquals("ROLE", views.get(1).getPrincipalType());
        assertTrue(views.get(1).isCanWrite());
        assertFalse(views.get(1).isCanRead()); // 默认 false，未被 set 成 true
    }
}
