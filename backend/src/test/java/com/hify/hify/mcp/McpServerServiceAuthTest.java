package com.hify.hify.mcp;

import com.hify.hify.common.exception.BizException;
import com.hify.hify.common.exception.ErrorCode;
import com.hify.hify.mcp.dto.McpServerCreateReq;
import com.hify.hify.mcp.McpServer;
import com.hify.hify.mcp.McpServerRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * McpServerService 权限单测（M7/T1，§3.2 权限、§7.11 规则38）。
 *
 * <p>大白话：增删改 MCP Server 配置是管理员专属操作。本测试把 SecurityContext 设成不同角色，
 * 断言——非 ADMIN / 未登录调写接口一律抛 FORBIDDEN（HTTP 403）且不碰仓库；ADMIN 放行落库。
 * 命名遵循 {@code test方法_场景_预期}（CLAUDE.md §7.10 规则34）。
 */
@ExtendWith(MockitoExtension.class)
class McpServerServiceAuthTest {

    @Mock
    private McpServerRepository repository;

    @InjectMocks
    private McpServerServiceImpl mcpServerService;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(String role) {
        var auth = new UsernamePasswordAuthenticationToken("tester", null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private McpServerCreateReq buildReq() {
        return new McpServerCreateReq("订单系统", "http://order.internal:8080/mcp", "SSE", 1);
    }

    @Test
    @DisplayName("createServer：非 ADMIN 抛 FORBIDDEN，且不落库")
    void testCreateServer_nonAdmin_throwsForbidden() {
        loginAs("USER");
        BizException ex = assertThrows(BizException.class, () -> mcpServerService.createServer(buildReq()));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("createServer：ADMIN 放行并落库")
    void testCreateServer_admin_passesAndSaves() {
        loginAs("ADMIN");
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        mcpServerService.createServer(buildReq());
        verify(repository).save(any());
    }

    @Test
    @DisplayName("updateServer：非 ADMIN 抛 FORBIDDEN，且不查库")
    void testUpdateServer_nonAdmin_throwsForbidden() {
        loginAs("USER");
        BizException ex = assertThrows(BizException.class, () -> mcpServerService.updateServer(1L, buildReq()));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
        verify(repository, never()).findById(anyLong());
    }

    @Test
    @DisplayName("deleteServer：非 ADMIN 抛 FORBIDDEN，且不查库")
    void testDeleteServer_nonAdmin_throwsForbidden() {
        loginAs("USER");
        BizException ex = assertThrows(BizException.class, () -> mcpServerService.deleteServer(1L));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
        verify(repository, never()).findById(anyLong());
    }

    @Test
    @DisplayName("createServer：未登录(匿名)抛 FORBIDDEN")
    void testCreateServer_anonymous_throwsForbidden() {
        BizException ex = assertThrows(BizException.class, () -> mcpServerService.createServer(buildReq()));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
    }
}
