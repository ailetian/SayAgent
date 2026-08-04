package com.hify.hify.agent.service;

import com.hify.hify.agent.dto.AgentCreateRequest;
import com.hify.hify.agent.dto.AgentUpdateRequest;
import com.hify.hify.agent.repository.AgentRepository;
import com.hify.hify.common.exception.BizException;
import com.hify.hify.common.exception.ErrorCode;
import com.hify.hify.modelprovider.service.ModelService;

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

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AgentService 权限单测（M4/T4，§3.2 权限、§7.11）。
 *
 * <p>大白话：增删改与「设默认」都是管理员专属操作。本测试把 SecurityContext 设成不同角色，
 * 断言——非 ADMIN / 未登录调写接口一律抛 FORBIDDEN 且不碰仓库；ADMIN 放行落库。
 * 命名遵循 {@code test方法_场景_预期}（CLAUDE.md §7.10 规则34）。
 */
@ExtendWith(MockitoExtension.class)
class AgentServiceAuthTest {

    @Mock
    private AgentRepository repository;

    @Mock
    private ModelService modelService; // 跨模块依赖：只认发布接口

    @InjectMocks
    private AgentService agentService;

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

    private AgentCreateRequest buildCreate() {
        return new AgentCreateRequest("n", "d", "s", 1L, "m",
                null, null, true, false, 0,
                BigDecimal.valueOf(0.70), BigDecimal.valueOf(1.00), 2048, 8192,
                null, null);
    }

    private AgentUpdateRequest buildUpdate() {
        return new AgentUpdateRequest("n", null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("createAgent：非 ADMIN 抛 FORBIDDEN，且不落库")
    void testCreateAgent_nonAdmin_throwsForbidden() {
        loginAs("USER");
        BizException ex = assertThrows(BizException.class, () -> agentService.createAgent(buildCreate()));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("createAgent：ADMIN 放行并落库")
    void testCreateAgent_admin_passesAndSaves() {
        loginAs("ADMIN");
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        agentService.createAgent(buildCreate());
        verify(repository).save(any());
    }

    @Test
    @DisplayName("updateAgent：非 ADMIN 抛 FORBIDDEN，且不查库")
    void testUpdateAgent_nonAdmin_throwsForbidden() {
        loginAs("USER");
        BizException ex = assertThrows(BizException.class, () -> agentService.updateAgent(1L, buildUpdate()));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
        verify(repository, never()).findById(anyLong());
    }

    @Test
    @DisplayName("deleteAgent：非 ADMIN 抛 FORBIDDEN，且不查库")
    void testDeleteAgent_nonAdmin_throwsForbidden() {
        loginAs("USER");
        BizException ex = assertThrows(BizException.class, () -> agentService.deleteAgent(1L));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
        verify(repository, never()).findById(anyLong());
    }

    @Test
    @DisplayName("setDefault：非 ADMIN 抛 FORBIDDEN，且不查库")
    void testSetDefault_nonAdmin_throwsForbidden() {
        loginAs("USER");
        BizException ex = assertThrows(BizException.class, () -> agentService.setDefault(1L));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
        verify(repository, never()).findById(anyLong());
    }

    @Test
    @DisplayName("createAgent：未登录(匿名)抛 FORBIDDEN")
    void testCreateAgent_anonymous_throwsForbidden() {
        BizException ex = assertThrows(BizException.class, () -> agentService.createAgent(buildCreate()));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
    }
}
