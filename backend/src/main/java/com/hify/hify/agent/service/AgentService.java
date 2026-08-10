package com.hify.hify.agent.service;

import com.hify.hify.agent.dto.AgentCreateRequest;
import com.hify.hify.agent.dto.AgentUpdateRequest;
import com.hify.hify.agent.dto.AgentVO;
import com.hify.hify.agent.entity.Agent;
import com.hify.hify.agent.repository.AgentRepository;
import com.hify.hify.common.exception.BizException;
import com.hify.hify.common.exception.ErrorCode;
import com.hify.hify.modelprovider.service.ModelService;
import com.hify.hify.skill.service.SkillService;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Agent 业务（M4/T2，§3.4 分层纪律：Controller 只调本服务，真正增删改查在这里）。
 *
 * <p>大白话：给运维一套管 Agent 的接口——列出、查看、新增、修改、删除、设默认。
 * <ul>
 *   <li>读（list/get）任何已登录用户可用；写（create/update/delete/setDefault）仅 ADMIN（§7.11 服务层再核）；</li>
 *   <li>对外一律返回 {@link AgentVO}，秘钥 secret/userPassword 被天然屏蔽（§7.11 规则37）；</li>
 *   <li>跨模块解耦（§3.3）：Agent 只认 modelprovider 发布的 {@link ModelService} 接口，
 *       在创建/修改时调用 {@code checkProviderIdExists} 校验「默认模型厂商」存在，
 *       <b>禁止</b> import modelprovider 的 Entity/Repository/Impl；</li>
 *   <li>找不到记录抛 {@code AGENT_NOT_FOUND}；删默认 Agent 等非法操作抛 {@code FORBIDDEN}（§7.3）。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AgentService {

    private final AgentRepository repository;

    /** 跨模块依赖：modelprovider 发布的「模型管理」API（仅此一处依赖，不触碰其内部实体/仓储）。 */
    private final ModelService modelService;

    /** 跨模块依赖：skill 发布的「技能管理」API（仅依赖 Service，不触碰 skill 内部 entity/repository，§3.2）。 */
    private final SkillService skillService;

    /** 列出全部 Agent（软删除已由 @SQLRestriction 过滤）。 */
    public List<AgentVO> listAgents() {
        return repository.findAll().stream()
                .map(AgentVO::from)
                .toList();
    }

    /** 查看单个 Agent；不存在抛 AGENT_NOT_FOUND。 */
    public AgentVO getAgent(Long id) {
        Agent a = repository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.AGENT_NOT_FOUND, "id=" + id));
        return AgentVO.from(a);
    }

    /** 新增 Agent（仅 ADMIN）；先跨模块校验模型厂商存在。 */
    public AgentVO createAgent(AgentCreateRequest req) {
        assertAdmin();
        modelService.checkProviderIdExists(req.modelProviderId());
        Agent a = new Agent();
        a.setName(req.name());
        a.setDescription(req.description() != null ? req.description() : "");
        a.setCreatedBy(currentUser());           // K7：记录创建者，挂载权限判定（创建者/admin 可改挂载）
        a.setSystemPrompt(req.systemPrompt());
        a.setModelProviderId(req.modelProviderId());
        a.setModel(req.model());
        a.setSecret(req.secret() != null ? req.secret() : "");
        a.setUserPassword(req.userPassword() != null ? req.userPassword() : "");
        a.setEnabled(req.enabled() != null ? req.enabled() : true);
        a.setSortOrder(req.sortOrder() != null ? req.sortOrder() : 0);
        a.setTemperature(req.temperature() != null ? req.temperature() : BigDecimal.valueOf(0.70));
        a.setTopP(req.topP() != null ? req.topP() : BigDecimal.valueOf(1.00));
        a.setMaxTokens(req.maxTokens() != null ? req.maxTokens() : 2048);
        a.setMaxContextTokens(req.maxContextTokens() != null ? req.maxContextTokens() : 8192);
        a.setKnowledgeRefs(req.knowledgeRefs() != null ? req.knowledgeRefs() : new ArrayList<>());
        a.setToolRefs(req.toolRefs() != null ? req.toolRefs() : new ArrayList<>());
        List<Long> skillRefs = req.skillRefs() != null ? req.skillRefs() : new ArrayList<>();
        for (Long sid : skillRefs) {
            skillService.assertExists(sid);          // 挂载的技能须存在且启用（§3.2）
        }
        a.setSkillRefs(skillRefs);
        boolean isDefault = req.defaultAgent() != null ? req.defaultAgent() : false;
        if (isDefault) {
            clearOldDefaultAndSetNew(a);
        } else {
            a.setDefaultAgent(false);
        }
        return AgentVO.from(repository.save(a));
    }

    /** 修改 Agent（仅 ADMIN）；仅更新请求中非 null 的字段。 */
    public AgentVO updateAgent(Long id, AgentUpdateRequest req) {
        assertAdmin();
        Agent a = repository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.AGENT_NOT_FOUND, "id=" + id));
        if (req.name() != null) {
            a.setName(req.name());
        }
        if (req.description() != null) {
            a.setDescription(req.description());
        }
        if (req.systemPrompt() != null) {
            a.setSystemPrompt(req.systemPrompt());
        }
        if (req.modelProviderId() != null) {
            modelService.checkProviderIdExists(req.modelProviderId());
            a.setModelProviderId(req.modelProviderId());
        }
        if (req.model() != null) {
            a.setModel(req.model());
        }
        if (req.secret() != null) {
            a.setSecret(req.secret());
        }
        if (req.userPassword() != null) {
            a.setUserPassword(req.userPassword());
        }
        if (req.enabled() != null) {
            a.setEnabled(req.enabled());
        }
        if (req.sortOrder() != null) {
            a.setSortOrder(req.sortOrder());
        }
        if (req.temperature() != null) {
            a.setTemperature(req.temperature());
        }
        if (req.topP() != null) {
            a.setTopP(req.topP());
        }
        if (req.maxTokens() != null) {
            a.setMaxTokens(req.maxTokens());
        }
        if (req.maxContextTokens() != null) {
            a.setMaxContextTokens(req.maxContextTokens());
        }
        if (req.knowledgeRefs() != null) {
            a.setKnowledgeRefs(req.knowledgeRefs());
        }
        if (req.toolRefs() != null) {
            a.setToolRefs(req.toolRefs());
        }
        if (req.skillRefs() != null) {
            for (Long sid : req.skillRefs()) {
                skillService.assertExists(sid);      // 挂载的技能须存在且启用（§3.2）
            }
            a.setSkillRefs(req.skillRefs());
        }
        if (req.defaultAgent() != null && req.defaultAgent()
                && !Boolean.TRUE.equals(a.getDefaultAgent())) {
            clearOldDefaultAndSetNew(a);
        }
        return AgentVO.from(repository.save(a));
    }

    /** 删除 Agent（仅 ADMIN）；默认 Agent 禁止删除（避免路由无主）。 */
    public void deleteAgent(Long id) {
        assertAdmin();
        Agent a = repository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.AGENT_NOT_FOUND, "id=" + id));
        if (Boolean.TRUE.equals(a.getDefaultAgent())) {
            throw new BizException(ErrorCode.FORBIDDEN, "默认 Agent 不可删除");
        }
        repository.delete(a);                      // 软删除（@SQLDelete）
    }

    /** 设默认 Agent（仅 ADMIN）；先把旧的默认取消，再把目标置为默认。 */
    public AgentVO setDefault(Long id) {
        assertAdmin();
        Agent target = repository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.AGENT_NOT_FOUND, "id=" + id));
        clearOldDefaultAndSetNew(target);
        return AgentVO.from(repository.save(target));
    }

    /** 取消旧默认（若存在且非目标）并置目标为默认。 */
    private void clearOldDefaultAndSetNew(Agent target) {
        repository.findByDefaultAgentTrue().ifPresent(old -> {
            if (!old.getId().equals(target.getId())) {
                old.setDefaultAgent(false);
                repository.save(old);
            }
        });
        target.setDefaultAgent(true);
    }

    /** 服务层权限再核（§7.11）：当前登录用户须为 ROLE_ADMIN，否则 FORBIDDEN。 */
    private void assertAdmin() {
        if (!isAdmin()) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅 ADMIN 可增删改 Agent");
        }
    }

    /**
     * 当前登录用户能否管理某 Agent 的知识库挂载（K7 挂载权，§3.5）。
     *
     * <p>大白话：挂载权 = Agent 创建者 或 管理员。供 knowledge 模块的 {@code MountService} 跨模块调用，
     * 不泄露 agent 内部实体/repo（只返 boolean，符合 §3.2 跨模块只依赖接口）。
     *
     * @param agentId 目标 Agent id
     * @return true = 可增删挂载；false = 既非创建者也非管理员
     */
    public boolean canManageMounts(Long agentId) {
        if (isAdmin()) {
            return true;
        }
        Agent agent = repository.findById(agentId)
                .orElseThrow(() -> new BizException(ErrorCode.AGENT_NOT_FOUND, "id=" + agentId));
        String me = currentUser();
        return me != null && me.equals(agent.getCreatedBy());
    }

    /** 当前登录用户是否管理员（ROLE_ADMIN）。 */
    private boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(au -> "ROLE_ADMIN".equals(au.getAuthority()));
    }

    /** 取当前登录用户名（AuthFilter 将 username 写入 SecurityContext principal）。 */
    private String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            return null;
        }
        return auth.getName();
    }

    /**
     * 找出所有挂载了某知识库的 Agent（删除知识库前的挂载校验，K0808）。
     *
     * <p>大白话：{@code knowledge_refs} 是 JSON 列，没有可靠派生查询，这里把全量 Agent 捞出来在内存里筛
     * （内部 20–50 人规模 Agent 极少，性能可忽略）。返回它们的 VO（天然屏蔽秘钥，§7.11）。
     */
    public List<AgentVO> findAgentsByKnowledgeRef(Long kbId) {
        return repository.findAll().stream()
                .filter(a -> a.getKnowledgeRefs() != null && a.getKnowledgeRefs().contains(kbId))
                .map(AgentVO::from)
                .toList();
    }

    /**
     * 把某知识库从所有 Agent 的 knowledgeRefs 里摘除并落库（删除知识库时同步清理挂载字段，双源一致，K0808）。
     */
    public void removeKnowledgeRefEverywhere(Long kbId) {
        List<Agent> affected = repository.findAll().stream()
                .filter(a -> a.getKnowledgeRefs() != null && a.getKnowledgeRefs().contains(kbId))
                .toList();
        for (Agent a : affected) {
            a.getKnowledgeRefs().remove(kbId);
            repository.save(a);
        }
    }

    /**
     * 把某知识库从指定 Agent 的 knowledgeRefs 里摘除并落库（卸载挂载时同步清理，消除字段与链接表双源脱钩，K0808）。
     */
    public void removeKnowledgeRefFromAgent(Long agentId, Long kbId) {
        Agent a = repository.findById(agentId)
                .orElseThrow(() -> new BizException(ErrorCode.AGENT_NOT_FOUND, "id=" + agentId));
        if (a.getKnowledgeRefs() != null && a.getKnowledgeRefs().remove(kbId)) {
            repository.save(a);
        }
    }
}
