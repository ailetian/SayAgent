package com.hify.hify.agent.web;

import com.hify.hify.agent.dto.AgentAccessGrantRequest;
import com.hify.hify.agent.dto.AgentCreateRequest;
import com.hify.hify.agent.dto.AgentUpdateRequest;
import com.hify.hify.agent.dto.AgentVO;
import com.hify.hify.agent.service.AgentService;
import com.hify.hify.agent.service.AgentToolSensitivity;
import com.hify.hify.common.Result;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Agent 管理接口「前台柜员」（M4/T2，§3.4 分层纪律：Controller 极薄——只收请求、调 service、装统一盒子）。
 *
 * <p>大白话：这是 {@code /api/agents} 一组入口。自己不写业务逻辑：
 * <ul>
 *   <li>鉴权第一道闸由 {@code SecurityConfig} 的 {@code anyRequest().authenticated()} 兜底——未登录直接 401；</li>
 *   <li>增删改的 ADMIN 权限在服务层 {@link AgentService} 再核（§7.11）；</li>
 *   <li>所有响应统一包 {@link Result}；失败由 {@code GlobalExceptionHandler} 翻译。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/agents")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;

    @GetMapping
    public Result<List<AgentVO>> listAgents() {
        return Result.ok(agentService.listAgents());
    }

    @GetMapping("/{id}")
    public Result<AgentVO> getAgent(@PathVariable Long id) {
        return Result.ok(agentService.getAgent(id));
    }

    @PostMapping
    public Result<AgentVO> createAgent(@Valid @RequestBody AgentCreateRequest request) {
        return Result.ok(agentService.createAgent(request));
    }

    @PutMapping("/{id}")
    public Result<AgentVO> updateAgent(@PathVariable Long id,
                                       @RequestBody AgentUpdateRequest request) {
        return Result.ok(agentService.updateAgent(id, request));
    }

    @DeleteMapping("/{id}")
    public Result<Void> deleteAgent(@PathVariable Long id) {
        agentService.deleteAgent(id);
        return Result.ok();
    }

    @PostMapping("/{id}/default")
    public Result<AgentVO> setDefault(@PathVariable Long id) {
        return Result.ok(agentService.setDefault(id));
    }

    /**
     * 列出某 Agent 携带的每个工具的「危险度 + 数据敏感度」快照（M10/T6，供授权页风险预览卡渲染）。
     * 数据由后端聚合（经 mcp 接口），前端不自行计算（§3.2 后端为真相源）。
     */
    @GetMapping("/{id}/tools")
    public Result<List<AgentToolSensitivity>> getAgentTools(@PathVariable Long id) {
        return Result.ok(agentService.listToolSensitivity(id));
    }

    /**
     * 授权某 Agent 给某主体（M10/T6，含风险摘要审计）。
     * 后端计算该 Agent 携带的敏感工具摘要并写审计（§7.11 留痕），不信任前端标记（§4 后端硬闸）。
     */
    @PostMapping("/{id}/access")
    public Result<Void> grantAccess(@PathVariable Long id,
                                    @Valid @RequestBody AgentAccessGrantRequest request) {
        agentService.grantAccess(id, request);
        return Result.ok();
    }
}
