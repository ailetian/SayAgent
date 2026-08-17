package com.hify.hify.agent.web;

import com.hify.hify.agent.dto.AgentCreateRequest;
import com.hify.hify.agent.dto.AgentUpdateRequest;
import com.hify.hify.agent.dto.AgentVO;
import com.hify.hify.agent.service.AgentService;
import com.hify.hify.common.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M4/T2 控制层单测（纯 Mockito，无 Spring 上下文）。
 *
 * <p>大白话：T2 的硬性纪律是「Controller 极薄——只收请求、调 Service、装 Result 盒子」，
 * 且绝不直接 import modelprovider 的内部类。本测试补齐此前被「口头标记通过」却未落地的
 * 10 个 T2 用例，使「全量 35 例」成为事实。
 */
@ExtendWith(MockitoExtension.class)
class AgentControllerTest {

    @Mock
    private AgentService agentService;

    @InjectMocks
    private AgentController agentController;

    /** AgentVO 是 record，用全参构造；这里只关心 id/name/defaultAgent，其余置 null。 */
    private AgentVO vo(Long id, String name, Boolean defaultAgent) {
        return new AgentVO(id, name, null, null, null, null, null, defaultAgent,
                null, null, null, null, null, null, null, null, null, null, 0L, 0L, null, null);
    }

    /** AgentCreateRequest/UpdateRequest 均为 record，用全参构造；仅填关心的字段。 */
    private AgentCreateRequest createReq(String name, Long modelProviderId) {
        return new AgentCreateRequest(name, null, null, modelProviderId, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
    }

    private AgentUpdateRequest updateReq(String name) {
        return new AgentUpdateRequest(name, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void testListAgents_serviceReturnsTwo_returnsResultWithList() {
        AgentVO vo1 = vo(1L, "a", null);
        AgentVO vo2 = vo(2L, "b", null);
        when(agentService.listAgents()).thenReturn(List.of(vo1, vo2));

        Result<List<AgentVO>> result = agentController.listAgents();

        assertEquals(0, result.getCode());
        assertNotNull(result.getData());
        assertEquals(2, result.getData().size());
        assertEquals("a", result.getData().get(0).name());
    }

    @Test
    void testGetAgent_serviceReturnsVO_returnsResultWithVO() {
        AgentVO vo = vo(7L, "x", null);
        when(agentService.getAgent(7L)).thenReturn(vo);

        Result<AgentVO> result = agentController.getAgent(7L);

        assertEquals(0, result.getCode());
        assertSame(vo, result.getData());
    }

    @Test
    void testCreateAgent_serviceReturnsVO_returnsResultWithVO() {
        AgentCreateRequest req = createReq("new", 1L);
        AgentVO vo = vo(10L, "new", null);
        when(agentService.createAgent(req)).thenReturn(vo);

        Result<AgentVO> result = agentController.createAgent(req);

        assertEquals(0, result.getCode());
        assertSame(vo, result.getData());
    }

    @Test
    void testUpdateAgent_serviceReturnsVO_returnsResultWithVO() {
        AgentUpdateRequest req = updateReq("upd");
        AgentVO vo = vo(11L, "upd", null);
        when(agentService.updateAgent(11L, req)).thenReturn(vo);

        Result<AgentVO> result = agentController.updateAgent(11L, req);

        assertEquals(0, result.getCode());
        assertSame(vo, result.getData());
    }

    @Test
    void testDeleteAgent_serviceOk_returnsResultSuccess() {
        Result<Void> result = agentController.deleteAgent(99L);

        assertEquals(0, result.getCode());
        assertNull(result.getData());
        verify(agentService).deleteAgent(99L);
    }

    @Test
    void testSetDefault_serviceReturnsVO_returnsResultWithVO() {
        AgentVO vo = vo(5L, "def", true);
        when(agentService.setDefault(5L)).thenReturn(vo);

        Result<AgentVO> result = agentController.setDefault(5L);

        assertEquals(0, result.getCode());
        assertSame(vo, result.getData());
    }

    @Test
    void testCreateAgent_requestPassedToService_delegatesCreateAgent() {
        AgentCreateRequest req = createReq("c", 2L);
        when(agentService.createAgent(any(AgentCreateRequest.class))).thenReturn(vo(1L, "c", null));

        agentController.createAgent(req);

        verify(agentService).createAgent(eq(req));
    }

    @Test
    void testUpdateAgent_idAndRequestPassedToService_delegatesUpdateAgent() {
        AgentUpdateRequest req = updateReq("u");
        when(agentService.updateAgent(eq(3L), any(AgentUpdateRequest.class))).thenReturn(vo(3L, "u", null));

        agentController.updateAgent(3L, req);

        verify(agentService).updateAgent(eq(3L), eq(req));
    }

    @Test
    void testController_importsOnlyAgentAndCommon_noModelproviderImport() throws IOException {
        Path ctrl = Path.of("src/main/java/com/hify/hify/agent/web/AgentController.java");
        assertTrue(Files.exists(ctrl), "AgentController.java 应存在");
        List<String> modelproviderImports = Files.readAllLines(ctrl).stream()
                .map(String::trim)
                .filter(l -> l.startsWith("import com.hify.hify.modelprovider"))
                .toList();
        assertTrue(modelproviderImports.isEmpty(),
                "Controller 禁止 import modelprovider 内部类（解耦纪律 §3.2），实际：" + modelproviderImports);
    }

    @Test
    void testAgentService_importsOnlyModelServiceFromModelprovider() throws IOException {
        Path svc = Path.of("src/main/java/com/hify/hify/agent/service/AgentService.java");
        assertTrue(Files.exists(svc), "AgentService.java 应存在");
        List<String> modelproviderImports = Files.readAllLines(svc).stream()
                .map(String::trim)
                .filter(l -> l.startsWith("import com.hify.hify.modelprovider"))
                .map(l -> l.replace("import ", "").replace(";", "").trim())
                .toList();
        assertEquals(List.of("com.hify.hify.modelprovider.service.ModelService"), modelproviderImports,
                "AgentService 跨模块只能依赖 ModelService 接口（解耦纪律 §3.2）");
    }
}
