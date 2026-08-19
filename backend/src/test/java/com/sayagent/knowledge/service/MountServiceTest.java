package com.sayagent.knowledge.service;

import com.sayagent.agent.service.AgentService;
import com.sayagent.common.exception.BizException;
import com.sayagent.common.exception.ErrorCode;
import com.sayagent.knowledge.entity.AgentKbLink;
import com.sayagent.knowledge.entity.KnowledgeBase;
import com.sayagent.knowledge.repository.AgentKbLinkRepository;
import com.sayagent.knowledge.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MountService（挂载 + 权限闸门）单测（K7）。
 *
 * <p>覆盖：挂载（创建者/admin 才有权）、幂等、kb 不存在、越权 FORBIDDEN、卸载（软删）、卸载未挂载、
 * 越权卸载、getMountedKbIds 隔离（未挂载返回空集）、isMounted。所有 repo 与 AgentService 均为 mock，
 * 不连真库（§7.10）；Agent 创建者/管理员判定经 {@link AgentService#canManageMounts} 跨模块接口，不触碰 agent 内部类。
 */
@ExtendWith(MockitoExtension.class)
class MountServiceTest {

    @Mock AgentKbLinkRepository linkRepository;
    @Mock KnowledgeBaseRepository kbRepository;
    @Mock AgentService agentService;

    private MountService mountService;

    @BeforeEach
    void setUp() {
        loginAs("alice"); // 默认登录用户
        mountService = new MountService(linkRepository, kbRepository, agentService);
    }

    /** 把当前登录身份写进 SecurityContext（AuthFilter 会把 username 放进 principal）。 */
    private void loginAs(String username, String... roles) {
        var authorities = Arrays.stream(roles)
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                .toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, null, authorities));
    }

    @Test
    void mount_creatorOrAdmin_createsLink_andRecordsOperator() {
        when(agentService.canManageMounts(1L)).thenReturn(true);
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(10L);
        when(kbRepository.findById(10L)).thenReturn(Optional.of(kb));
        when(linkRepository.existsByAgentIdAndKbId(1L, 10L)).thenReturn(false);

        boolean created = mountService.mount(1L, 10L);

        assertTrue(created);
        ArgumentCaptor<AgentKbLink> captor = ArgumentCaptor.forClass(AgentKbLink.class);
        verify(linkRepository).save(captor.capture());
        AgentKbLink saved = captor.getValue();
        assertEquals(1L, saved.getAgentId());
        assertEquals(10L, saved.getKbId());
        assertEquals("alice", saved.getCreatedBy(), "挂载操作人应记为当前登录用户");
    }

    @Test
    void mount_idempotent_returnsFalse_withoutSaving() {
        when(agentService.canManageMounts(1L)).thenReturn(true);
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(10L);
        when(kbRepository.findById(10L)).thenReturn(Optional.of(kb));
        when(linkRepository.existsByAgentIdAndKbId(1L, 10L)).thenReturn(true); // 已挂载

        boolean created = mountService.mount(1L, 10L);

        assertFalse(created, "已挂载应幂等返回 false");
        verify(linkRepository, never()).save(any(AgentKbLink.class));
    }

    @Test
    void mount_kbNotFound_throwsKnowledgeBaseNotFound() {
        when(agentService.canManageMounts(1L)).thenReturn(true);
        when(kbRepository.findById(10L)).thenReturn(Optional.empty());

        BizException ex = assertThrows(BizException.class, () -> mountService.mount(1L, 10L));
        assertEquals(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, ex.getErrorCode());
        verify(linkRepository, never()).save(any(AgentKbLink.class));
    }

    @Test
    void mount_nonCreatorNonAdmin_throwsForbidden() {
        when(agentService.canManageMounts(1L)).thenReturn(false); // 既非创建者也非管理员

        BizException ex = assertThrows(BizException.class, () -> mountService.mount(1L, 10L));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode(), "非创建者/管理员不可挂载");
        verify(linkRepository, never()).save(any(AgentKbLink.class));
    }

    @Test
    void unmount_success_softDeletesLink() {
        when(agentService.canManageMounts(1L)).thenReturn(true);
        AgentKbLink link = new AgentKbLink();
        link.setAgentId(1L);
        link.setKbId(10L);
        when(linkRepository.findByAgentIdAndKbId(1L, 10L)).thenReturn(Optional.of(link));

        mountService.unmount(1L, 10L);

        verify(linkRepository).delete(link); // 软删（@SQLDelete → deleted=1）
        // 双源一致：链接表软删的同时，必须把该 kb 从 Agent.knowledgeRefs 字段摘掉，
        // 否则字段仍指向已卸载的库，聊天检索会继续把它算进去（K0808 暴露的双源脱钩隐患）。
        verify(agentService).removeKnowledgeRefFromAgent(1L, 10L);
    }

    @Test
    void unmount_notMounted_throwsResourceNotFound() {
        when(agentService.canManageMounts(1L)).thenReturn(true);
        when(linkRepository.findByAgentIdAndKbId(1L, 10L)).thenReturn(Optional.empty());

        BizException ex = assertThrows(BizException.class, () -> mountService.unmount(1L, 10L));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, ex.getErrorCode());
        verify(linkRepository, never()).delete(any(AgentKbLink.class));
    }

    @Test
    void unmount_nonCreatorNonAdmin_throwsForbidden() {
        when(agentService.canManageMounts(1L)).thenReturn(false);

        BizException ex = assertThrows(BizException.class, () -> mountService.unmount(1L, 10L));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode(), "非创建者/管理员不可卸载");
        verify(linkRepository, never()).delete(any(AgentKbLink.class));
    }

    @Test
    void getMountedKbIds_returnsLinkedKbs() {
        AgentKbLink l1 = new AgentKbLink();
        l1.setKbId(10L);
        AgentKbLink l2 = new AgentKbLink();
        l2.setKbId(20L);
        when(linkRepository.findByAgentId(1L)).thenReturn(List.of(l1, l2));

        List<Long> ids = mountService.getMountedKbIds(1L);

        assertEquals(List.of(10L, 20L), ids, "应返回该 Agent 挂载的全部 kb（检索隔离维度）");
    }

    @Test
    void getMountedKbIds_unmountedAgent_returnsEmpty_isolation() {
        when(linkRepository.findByAgentId(99L)).thenReturn(List.of()); // 未挂任何库

        List<Long> ids = mountService.getMountedKbIds(99L);

        assertTrue(ids.isEmpty(), "未挂载任何库 → 空集（下游 RagQueryService 据此 NO_KB 拒答）");
    }

    @Test
    void isMounted_reflectsLinkState() {
        when(linkRepository.existsByAgentIdAndKbId(1L, 10L)).thenReturn(true);
        when(linkRepository.existsByAgentIdAndKbId(1L, 20L)).thenReturn(false);

        assertTrue(mountService.isMounted(1L, 10L));
        assertFalse(mountService.isMounted(1L, 20L));
        verify(linkRepository, times(2)).existsByAgentIdAndKbId(anyLong(), anyLong());
    }
}
