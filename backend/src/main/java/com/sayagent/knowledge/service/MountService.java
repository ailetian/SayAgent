package com.sayagent.knowledge.service;

import com.sayagent.agent.service.AgentService;
import com.sayagent.common.exception.BizException;
import com.sayagent.common.exception.ErrorCode;
import com.sayagent.knowledge.entity.AgentKbLink;
import com.sayagent.knowledge.repository.AgentKbLinkRepository;
import com.sayagent.knowledge.repository.KnowledgeBaseRepository;

import lombok.extern.slf4j.Slf4j;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 挂载与权限闸门（K7）：Agent ↔ 知识库 多对多挂载 + 检索鉴权链。
 *
 * <p>大白话：一个 Agent 能查哪些库由「挂载关系」决定，权限委托给 Agent——
 * <ul>
 *   <li><b>挂载权</b>：仅 Agent 创建者/admin（经 {@link AgentService#canManageMounts} 跨模块判定，§3.2 只依赖接口）；</li>
 *   <li><b>管理权</b>：知识库本身由 {@code KB.creatorId + admin} 管（见 {@code KnowledgeService}）；</li>
 *   <li><b>查询权</b>：检索时只允许查「该 Agent 挂载且未软删」的库（{@link #getMountedKbIds} 供 K4/K5 检索过滤），
 *       没挂的库对该 Agent 永远不可见（隔离），挂载库都没命中才走 K5 的 {@code NO_KB}/阈值拒答。</li>
 * </ul>
 * 本服务不触碰任何 agent 内部类（实体/repo/impl），只认 {@link AgentService} 接口，符合 §3.2 跨模块纪律。
 */
@Service
@Slf4j
public class MountService {

    private final AgentKbLinkRepository linkRepository;
    private final KnowledgeBaseRepository kbRepository;
    /** 跨模块依赖：agent 模块发布的「Agent 管理」API，仅此一处依赖，不触碰其内部实体/repo（§3.2）。 */
    private final AgentService agentService;

    public MountService(AgentKbLinkRepository linkRepository,
                        KnowledgeBaseRepository kbRepository,
                        AgentService agentService) {
        this.linkRepository = linkRepository;
        this.kbRepository = kbRepository;
        this.agentService = agentService;
    }

    /**
     * 挂载知识库到 Agent（幂等）：仅 Agent 创建者/admin 可操作。
     *
     * @return true=新建了挂载；false=原本就已挂载（幂等，不重复建行）
     */
    public boolean mount(Long agentId, Long kbId) {
        assertCanManage(agentId);
        kbRepository.findById(kbId)
                .orElseThrow(() -> new BizException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND));
        if (linkRepository.existsByAgentIdAndKbId(agentId, kbId)) {
            return false; // 已挂载（软删后可重建，此处仅判有效挂载）
        }
        AgentKbLink link = new AgentKbLink();
        link.setAgentId(agentId);
        link.setKbId(kbId);
        link.setCreatedBy(currentUser());
        linkRepository.save(link);
        log.info("mount agentId={} kbId={} by={}", agentId, kbId, currentUser());
        return true;
    }

    /**
     * 卸载某 Agent 的某知识库（软删）：仅 Agent 创建者/admin 可操作。
     */
    public void unmount(Long agentId, Long kbId) {
        assertCanManage(agentId);
        AgentKbLink link = linkRepository.findByAgentIdAndKbId(agentId, kbId)
                .orElseThrow(() -> new BizException(ErrorCode.RESOURCE_NOT_FOUND, "该 Agent 未挂载此知识库"));
        linkRepository.delete(link); // 软删（@SQLDelete → deleted=1）
        agentService.removeKnowledgeRefFromAgent(agentId, kbId); // 同步清字段，消除双源脱钩（K0808）
        log.info("unmount agentId={} kbId={} by={}", agentId, kbId, currentUser());
    }

    /**
     * 返回该 Agent 挂载且未删的知识库 id 集合（检索隔离维度，供 K4/K5 检索过滤用）。
     */
    public List<Long> getMountedKbIds(Long agentId) {
        return linkRepository.findByAgentId(agentId).stream()
                .map(AgentKbLink::getKbId)
                .toList();
    }

    /** 该 Agent 是否挂载了某知识库（检索隔离判定）。 */
    public boolean isMounted(Long agentId, Long kbId) {
        return linkRepository.existsByAgentIdAndKbId(agentId, kbId);
    }

    /** 鉴权：仅 Agent 创建者/admin 可改挂载；否则 FORBIDDEN（§3.5 挂载权）。 */
    private void assertCanManage(Long agentId) {
        if (!agentService.canManageMounts(agentId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅 Agent 创建者/管理员可管理挂载");
        }
    }

    /** 取当前登录用户名（AuthFilter 将 username 写入 SecurityContext principal）。 */
    private String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return auth.getName();
    }
}
