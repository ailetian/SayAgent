package com.hify.hify.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hify.hify.agent.dto.AgentCreateRequest;
import com.hify.hify.agent.dto.AgentUpdateRequest;
import com.hify.hify.agent.dto.AgentVO;
import com.hify.hify.agent.entity.Agent;
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

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AgentService 单测（mock 仓库 + 跨模块 ModelService，不连真实库，§7.10 规则35）。
 *
 * <p>聚焦：CRUD 链路、ADMIN 服务层校验、跨模块校验模型厂商存在（只调 ModelService 接口，
 * 不碰 modelprovider 内部实体/仓储）、默认 Agent 互斥、秘钥脱敏。
 * 命名遵循 {@code test方法_场景_预期}（AGENTS.md §7.10 规则34）。
 */
@ExtendWith(MockitoExtension.class)
class AgentServiceTest {

    @Mock
    private AgentRepository repository;

    @Mock
    private ModelService modelService;   // 跨模块依赖：只认发布接口

    @InjectMocks
    private AgentService agentService;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

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

    private Agent sample(Long id, Long providerId, boolean enabled, boolean isDefault) {
        Agent a = new Agent();
        a.setId(id);
        a.setName("a" + id);
        a.setDescription("desc" + id);
        a.setSystemPrompt("sys" + id);
        a.setModelProviderId(providerId);
        a.setModel("model-" + id);
        a.setSecret("secret-" + id);
        a.setUserPassword("pw-" + id);
        a.setEnabled(enabled);
        a.setDefaultAgent(isDefault);
        a.setSortOrder(0);
        a.setTemperature(BigDecimal.valueOf(0.70));
        a.setTopP(BigDecimal.valueOf(1.00));
        a.setMaxTokens(2048);
        a.setMaxContextTokens(8192);
        return a;
    }

    @Test
    @DisplayName("listAgents：仓库返回两条，转成两个 VO")
    void testListAgents_repositoryReturnsTwo_returnsTwoVOs() {
        when(repository.findAll()).thenReturn(List.of(sample(1L, 1L, true, false),
                sample(2L, 1L, true, false)));

        List<AgentVO> vos = agentService.listAgents();

        assertEquals(2, vos.size());
        assertEquals("a1", vos.get(0).name());
        verify(repository).findAll();
    }

    @Test
    @DisplayName("getAgent：存在 id，返回对应 VO")
    void testGetAgent_existingId_returnsVO() {
        when(repository.findById(1L)).thenReturn(Optional.of(sample(1L, 1L, true, false)));

        AgentVO vo = agentService.getAgent(1L);

        assertEquals(1L, vo.id());
        assertEquals(1L, vo.modelProviderId());
    }

    @Test
    @DisplayName("getAgent：id 不存在，抛 AGENT_NOT_FOUND")
    void testGetAgent_missingId_throwsAgentNotFound() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        BizException ex = assertThrows(BizException.class, () -> agentService.getAgent(99L));
        assertEquals(ErrorCode.AGENT_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    @DisplayName("createAgent：ADMIN 角色，跨模块校验厂商存在后落库并返回 VO")
    void testCreateAgent_adminRole_checksProviderAndSaves() {
        loginAs("ADMIN");
        AgentCreateRequest req = new AgentCreateRequest("gpt", "desc", "sys",
                1L, "gpt-4o", null, null, true, false, 0,
                BigDecimal.valueOf(0.70), BigDecimal.valueOf(1.00), 2048, 8192,
                null, null);
        when(repository.save(any(Agent.class))).thenAnswer(inv -> inv.getArgument(0));

        AgentVO vo = agentService.createAgent(req);

        assertEquals("gpt", vo.name());
        assertEquals(1L, vo.modelProviderId());
        verify(modelService).checkProviderIdExists(1L); // 跨模块解耦：只调接口
        verify(repository).save(any(Agent.class));
    }

    @Test
    @DisplayName("createAgent：非 ADMIN，抛 FORBIDDEN 且不校验厂商")
    void testCreateAgent_nonAdminRole_throwsForbidden() {
        loginAs("USER");
        AgentCreateRequest req = new AgentCreateRequest("gpt", "desc", "sys",
                1L, "gpt-4o", null, null, true, false, 0,
                null, null, null, null, null, null);

        BizException ex = assertThrows(BizException.class, () -> agentService.createAgent(req));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
        verify(modelService, never()).checkProviderIdExists(anyLong());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("updateAgent：存在 id，更新非空字段并返回 VO")
    void testUpdateAgent_existingId_updatesFieldsAndReturnsVO() {
        loginAs("ADMIN");
        Agent existing = sample(1L, 1L, true, false);
        when(repository.findById(1L)).thenReturn(Optional.of(existing));
        when(repository.save(any(Agent.class))).thenAnswer(inv -> inv.getArgument(0));
        AgentUpdateRequest req = new AgentUpdateRequest("a-new", null, null,
                null, "gpt-4o-mini", null, null, null, null, null, null, null, null, null, null, null);

        AgentVO vo = agentService.updateAgent(1L, req);

        assertEquals("a-new", vo.name());
        assertEquals("gpt-4o-mini", vo.model());
        assertEquals(true, vo.enabled()); // 未传保持原值
    }

    @Test
    @DisplayName("deleteAgent：默认 Agent，抛 FORBIDDEN 不删除")
    void testDeleteAgent_defaultAgent_throwsForbidden() {
        loginAs("ADMIN");
        when(repository.findById(1L)).thenReturn(Optional.of(sample(1L, 1L, true, true)));

        BizException ex = assertThrows(BizException.class, () -> agentService.deleteAgent(1L));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
        verify(repository, never()).delete(any());
    }

    @Test
    @DisplayName("deleteAgent：非默认且 ADMIN，执行软删除")
    void testDeleteAgent_nonDefault_adminRole_softDeletes() {
        loginAs("ADMIN");
        Agent a = sample(1L, 1L, true, false);
        when(repository.findById(1L)).thenReturn(Optional.of(a));

        agentService.deleteAgent(1L);

        verify(repository).delete(a);
    }

    @Test
    @DisplayName("setDefault：存在 id，取消旧默认并置新默认")
    void testSetDefault_existingId_clearsOldAndSetsNew() {
        loginAs("ADMIN");
        Agent oldDefault = sample(1L, 1L, true, true);
        Agent target = sample(2L, 1L, true, false);
        when(repository.findById(2L)).thenReturn(Optional.of(target));
        when(repository.findByDefaultAgentTrue()).thenReturn(Optional.of(oldDefault));
        when(repository.save(any(Agent.class))).thenAnswer(inv -> inv.getArgument(0));

        AgentVO vo = agentService.setDefault(2L);

        assertFalse(oldDefault.getDefaultAgent());
        assertEquals(true, vo.defaultAgent());
        verify(repository, times(2)).save(any(Agent.class));
    }

    @Test
    @DisplayName("序列化：VO 不含 secret/userPassword 明文（§7.11 脱敏）")
    void testSerialization_voExcludesSecretAndPassword() throws Exception {
        Agent a = sample(1L, 1L, true, false);
        AgentVO vo = AgentVO.from(a);

        String json = objectMapper.writeValueAsString(vo);

        assertFalse(json.contains("secret"), "响应 JSON 不应含 secret 字段");
        assertFalse(json.contains("secret-1"), "响应 JSON 不应含秘钥明文");
        assertFalse(json.contains("userPassword"), "响应 JSON 不应含 userPassword 字段");
        assertFalse(json.contains("pw-1"), "响应 JSON 不应含密码明文");
        assertTrue(json.contains("name"), "响应 JSON 应含普通字段");
    }

    @Test
    @DisplayName("createAgent：带 knowledgeRefs/toolRefs，返回 VO 含同样引用列表（§3.5 出参含引用）")
    void testCreateAgent_withRefs_returnsVOWithRefs() {
        loginAs("ADMIN");
        AgentCreateRequest req = new AgentCreateRequest("gpt", "desc", "sys",
                1L, "gpt-4o", null, null, true, false, 0,
                BigDecimal.valueOf(0.70), BigDecimal.valueOf(1.00), 2048, 8192,
                List.of(10L, 20L), List.of(30L));
        when(repository.save(any(Agent.class))).thenAnswer(inv -> inv.getArgument(0));

        AgentVO vo = agentService.createAgent(req);

        assertEquals(List.of(10L, 20L), vo.knowledgeRefs());
        assertEquals(List.of(30L), vo.toolRefs());
    }

    @Test
    @DisplayName("解耦：agent 包只依赖 modelprovider 的 ModelService 接口，不 import 其内部类（§3.2 硬约束）")
    void testAgentPackage_onlyImportsModelServiceFromModelProvider() throws Exception {
        URL loc = AgentService.class.getProtectionDomain().getCodeSource().getLocation();
        File moduleRoot = new File(loc.toURI()).getParentFile().getParentFile();
        File agentRoot = new File(moduleRoot, "src/main/java/com/hify/hify/agent");
        assertTrue(agentRoot.isDirectory(), "agent 源码目录应存在: " + agentRoot);
        List<String> violations = new ArrayList<>();
        Files.walk(agentRoot.toPath())
                .filter(p -> p.toString().endsWith(".java"))
                .forEach(p -> {
                    try {
                        for (String line : Files.readAllLines(p)) {
                            if (line.contains("import") && line.contains("com.hify.hify.modelprovider")
                                    && !line.contains("ModelService")) {
                                violations.add(p.getFileName() + " -> " + line.trim());
                            }
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
        assertTrue(violations.isEmpty(),
                "agent 包越界依赖了 modelprovider 内部类:\n" + String.join("\n", violations));
    }
}
