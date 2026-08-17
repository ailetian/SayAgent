package com.hify.hify.agent.service;

import com.hify.hify.agent.dto.AgentAccessGrantRequest;
import com.hify.hify.agent.dto.AgentCreateRequest;
import com.hify.hify.agent.dto.AgentUpdateRequest;
import com.hify.hify.agent.dto.AgentVO;
import com.hify.hify.agent.entity.Agent;
import com.hify.hify.agent.repository.AgentRepository;
import com.hify.hify.common.exception.BizException;
import com.hify.hify.common.exception.ErrorCode;
import com.hify.hify.common.security.AuthContext;
import com.hify.hify.common.tool.DataSensitivity;
import com.hify.hify.common.tool.RiskLevel;
import com.hify.hify.mcp.McpService;
import com.hify.hify.mcp.dto.ToolDefinition;
import com.hify.hify.modelprovider.service.ModelService;
import com.hify.hify.rbac.ResourceAccessService;
import com.hify.hify.skill.service.SkillService;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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

    /** T5：资源授权服务（rbac），建 Agent 后给创建者本人写全权授权行（跨模块，接口级依赖 §3.2）。 */
    private final ResourceAccessService resourceAccessService;

    /** T4：跨模块依赖 mcp 发布的「工具发现」API（仅接口，不触碰 McpClientManager/McpServer 内部类，§3.2）。
     *  用于聚合某 Agent 挂载 MCP Server 旗下工具的数据敏感度/危险度，供 T6 授权页知情（§2.1 授权知情）。 */
    private final McpService mcpService;

    /**
     * 列出全部 Agent（T6 可见性过滤 §2.1：ADMIN 看全部，普通用户只看到 PUBLIC ∪ 自己被授权；软删除已由 @SQLRestriction 过滤）。
     *
     * <p>大白话：和知识库列表同理——管理员能看到所有 Agent（含 RESTRICTED），普通用户只能看到
     * {@code visibility='PUBLIC'} 的或自己被 {@code resource_access} 显式授权的。复用 T5 的
     * {@link ResourceAccessService#visibleResourceIds} 拿授权 id 集合。
     */
    public List<AgentVO> listAgents() {
        if (AuthContext.isAdmin()) {
            return repository.findAll().stream()
                    .map(a -> AgentVO.from(a, aggregateSensitivity(a)))
                    .toList();
        }
        Set<Long> visibleIds = computeVisibleAgentIds();
        if (visibleIds.isEmpty()) {
            return List.of();
        }
        return repository.findByIdInOrderByIdDesc(visibleIds).stream()
                .map(a -> AgentVO.from(a, aggregateSensitivity(a)))
                .toList();
    }

    /**
     * 计算当前用户可见 Agent id = PUBLIC ∪ 显式授权（T5 visibleResourceIds）；ADMIN 不走此方法。
     */
    private Set<Long> computeVisibleAgentIds() {
        Set<Long> ids = new LinkedHashSet<>(
                repository.findIdsByVisibility(Agent.VISIBILITY_PUBLIC));
        Set<Long> granted = resourceAccessService.visibleResourceIds(
                AuthContext.currentUsername(), AuthContext.roleSet(), ResourceAccessService.RESOURCE_AGENT);
        if (granted != null) {
            ids.addAll(granted);
        }
        return ids;
    }

    /**
     * 可见性闸门（§2.1：ADMIN 全权 / PUBLIC 全员可见 / 否则须有显式授权=个人∪角色）。
     * 不可访问直接抛 {@code FORBIDDEN}。
     * <p>大白话：列表看不到的 Agent，不能直接拿 id 绕过——对话入口（{@code getAgent}）和
     * {@code GET /api/agents/{id}} 都要过这关，堵住"知道 id 就能用/拉配置"的越权。
     */
    public void assertAccessible(Long agentId) {
        if (AuthContext.isAdmin()) {
            return; // ADMIN 对所有 Agent 隐式全权（含 RESTRICTED）
        }
        Agent a = repository.findById(agentId)
                .orElseThrow(() -> new BizException(ErrorCode.AGENT_NOT_FOUND, "id=" + agentId));
        if (Agent.VISIBILITY_PUBLIC.equals(a.getVisibility())) {
            return;
        }
        Set<Long> granted = resourceAccessService.visibleResourceIds(
                AuthContext.currentUsername(), AuthContext.roleSet(), ResourceAccessService.RESOURCE_AGENT);
        if (granted != null && granted.contains(agentId)) {
            return;
        }
        throw new BizException(ErrorCode.FORBIDDEN, "无权使用该 Agent: " + agentId);
    }

    /** 查看单个 Agent；不存在抛 AGENT_NOT_FOUND，无权限抛 FORBIDDEN（§2.1 可见性）。 */
    public AgentVO getAgent(Long id) {
        assertAccessible(id);
        Agent a = repository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.AGENT_NOT_FOUND, "id=" + id));
        return AgentVO.from(a, aggregateSensitivity(a));
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
        Agent saved = repository.save(a);
        // T5：创建者自授权——给创建者本人写一行全权授权（保证自己能继续管理，§2.1 创建者权益）
        resourceAccessService.grantCreator(ResourceAccessService.RESOURCE_AGENT, saved.getId(), saved.getCreatedBy());
        return AgentVO.from(saved);
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

    /**
     * 聚合某 Agent 携带工具的数据敏感度（M10/T4，§2.1 授权知情）。
     *
     * <p>大白话：把这个 Agent 挂的 MCP Server 旗下所有工具的「数据敏感度 + 危险度」取最高级，
     * 算出"本 Agent 含哪些敏感域工具、最高到什么级别"，供 T6 前端授权页摊开知情、高危须强制确认。
     * 内置工具（current-time 等）默认 INTERNAL / L0，作为地板；仅当挂载了高敏感 MCP 工具时才会升高。
     *
     * <p>跨模块纪律（§3.2）：仅经 mcp 已发布的 {@link McpService#listTools(Long)} 接口取工具定义，
     * <b>禁止</b> import conversation 的 {@code ToolRegistry}/{@code McpToolAdapter} 等实现类。
     * MCP 工具的 dataSensitivity 由管理员在注册 Server 时人工标注（server 级），经
     * {@code McpClientManager → McpToolAdapter} 透传到工具定义（本方法只读结果）。
     *
     * @param agentId Agent id
     * @return 聚合结果（最高数据敏感度、最高危险度、含 FINANCE_HR/CONFIDENTIAL 工具数）
     */
    public AgentSensitivitySummary aggregateSensitivity(Long agentId) {
        Agent a = repository.findById(agentId)
                .orElseThrow(() -> new BizException(ErrorCode.AGENT_NOT_FOUND, "id=" + agentId));
        return aggregateSensitivity(a);
    }

    /**
     * 聚合（接收已加载实体，避免列表场景重复查库）。
     */
    private AgentSensitivitySummary aggregateSensitivity(Agent a) {
        // 地板：内置工具默认 INTERNAL / L0（即便没有挂载任何 MCP Server 也至少这个级别）
        DataSensitivity maxDs = DataSensitivity.INTERNAL;
        RiskLevel maxRl = RiskLevel.L0_READONLY_SAFE;
        long financeHrCount = 0;
        long confidentialCount = 0;
        if (a.getToolRefs() != null) {
            for (Long serverId : a.getToolRefs()) {
                List<ToolDefinition> defs = mcpService.listTools(serverId); // 失败内部降级为空列表，不抛（§4.5）
                if (defs != null) {
                    for (ToolDefinition td : defs) {
                        DataSensitivity ds = td.dataSensitivity();
                        RiskLevel rl = td.riskLevel();
                        if (ds != null && ds.ordinal() > maxDs.ordinal()) {
                            maxDs = ds;
                        }
                        if (rl != null && rl.ordinal() > maxRl.ordinal()) {
                            maxRl = rl;
                        }
                        if (ds == DataSensitivity.FINANCE_HR) {
                            financeHrCount++;
                        } else if (ds == DataSensitivity.CONFIDENTIAL) {
                            confidentialCount++;
                        }
                    }
                }
            }
        }
        return new AgentSensitivitySummary(maxDs, maxRl, financeHrCount, confidentialCount);
    }

    /**
     * 列出某 Agent 携带的每个工具的「危险度 + 数据敏感度」快照（M10/T6，供前端授权页风险预览卡渲染）。
     *
     * <p>大白话：把上面的聚合摊开成逐条明细——前端据此逐工具展示色标（如「读薪酬 L0 财务·人事」）。
     * 数据来源与 {@link #aggregateSensitivity} 完全一致（仅经 mcp 接口取，不反向依赖 conversation，§3.2）。
     *
     * @param agentId Agent id
     * @return 每个工具一行（name / description / riskLevel 枚举名 / dataSensitivity 枚举名）
     */
    public List<AgentToolSensitivity> listToolSensitivity(Long agentId) {
        Agent a = repository.findById(agentId)
                .orElseThrow(() -> new BizException(ErrorCode.AGENT_NOT_FOUND, "id=" + agentId));
        List<AgentToolSensitivity> result = new ArrayList<>();
        if (a.getToolRefs() != null) {
            for (Long serverId : a.getToolRefs()) {
                List<ToolDefinition> defs = mcpService.listTools(serverId); // 降级空列表，不抛（§4.5）
                if (defs != null) {
                    for (ToolDefinition td : defs) {
                        String rl = td.riskLevel() != null ? td.riskLevel().name() : RiskLevel.L1_WRITE_REVERSIBLE.name();
                        String ds = td.dataSensitivity() != null ? td.dataSensitivity().name() : DataSensitivity.INTERNAL.name();
                        result.add(new AgentToolSensitivity(td.name(), td.description(), rl, ds));
                    }
                }
            }
        }
        return result;
    }

    /**
     * 生成授权审计用的「资源风险摘要」文案（M10/T6，§7.11 留痕）。
     *
     * <p>大白话：把该 Agent 携带的敏感/高危工具浓缩成一句话，如
     * 「含敏感域工具2个: 读薪酬(财务·人事)/调薪资(财务·人事); 含高危工具1个: 取消订单(L2不可逆)」。
     * 仅当确实含敏感/高危工具时才写具体工具，否则写「无敏感/高危工具」（便于审计一眼看清）。
     *
     * @param agentId Agent id
     * @return 风险摘要文案（非空）
     */
    private String describeAgentRisk(Long agentId) {
        List<AgentToolSensitivity> tools = listToolSensitivity(agentId);
        List<String> sensitive = new ArrayList<>();
        List<String> highRisk = new ArrayList<>();
        for (AgentToolSensitivity t : tools) {
            if ("FINANCE_HR".equals(t.dataSensitivity()) || "CONFIDENTIAL".equals(t.dataSensitivity())) {
                sensitive.add(t.name() + "(" + dsDesc(t.dataSensitivity()) + ")");
            }
            if ("L2_IRREVERSIBLE".equals(t.riskLevel()) || "L3_HIGH_RISK".equals(t.riskLevel())) {
                highRisk.add(t.name() + "(" + rlDesc(t.riskLevel()) + ")");
            }
        }
        StringBuilder sb = new StringBuilder();
        if (!sensitive.isEmpty()) {
            sb.append("含敏感域工具").append(sensitive.size()).append("个: ").append(String.join("/", sensitive));
        }
        if (!highRisk.isEmpty()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append("含高危工具").append(highRisk.size()).append("个: ").append(String.join("/", highRisk));
        }
        return sb.length() > 0 ? sb.toString() : "无敏感/高危工具";
    }

    /** 数据敏感度枚举名 → 中文含义（审计文案用）。 */
    private String dsDesc(String name) {
        try {
            return DataSensitivity.valueOf(name).desc;
        } catch (IllegalArgumentException e) {
            return name;
        }
    }

    /** 危险度枚举名 → 中文含义（审计文案用）。 */
    private String rlDesc(String name) {
        try {
            return RiskLevel.valueOf(name).desc;
        } catch (IllegalArgumentException e) {
            return name;
        }
    }

    /**
     * 授权某 Agent 给某主体，并写含风险摘要的审计（M10/T6，§2.1 授权知情 + §7.11 留痕）。
     *
     * <p>大白话：管理员在授权页点「确认」后，这里先核权限（仅管理员/管理者可授权，禁止给 ADMIN 角色授权），
     * 算出该 Agent 携带的敏感工具摘要，再委托 rbac 落库 + 写审计。审计摘要由后端计算，<b>不信任前端</b>任何标记，
     * 满足 §4「后端硬闸」要求。
     *
     * @param agentId Agent id
     * @param req     授权请求（主体类型/id + 四权）
     */
    @Transactional
    public void grantAccess(Long agentId, AgentAccessGrantRequest req) {
        // 权限核：仅 ADMIN 或该 Agent 管理者可授权（rbac 服务层再核，§7.11）
        resourceAccessService.requireManager(ResourceAccessService.RESOURCE_AGENT, agentId);
        // 后端计算风险摘要（不依赖前端），再委托 rbac 落库 + 写审计（grant 内部会再拦 ADMIN 主体）
        String riskSummary = describeAgentRisk(agentId);
        resourceAccessService.grant(req.principalType(), req.principalId(), ResourceAccessService.RESOURCE_AGENT,
                agentId, req.canRead(), req.canWrite(), req.canUse(), req.canEdit(), riskSummary);
    }

    /**
     * Agent 聚合敏感度结果（M10/T4，供 T6 前端授权页知情）。
     *
     * @param maxDataSensitivity 本 Agent 工具最高数据敏感度（PUBLIC→FINANCE_HR 递增）
     * @param maxRiskLevel       本 Agent 工具最高危险度（L0→L3 递增）
     * @param financeHrToolCount 含 FINANCE_HR 域工具的数量
     * @param confidentialToolCount 含 CONFIDENTIAL 域工具的数量
     */
    public record AgentSensitivitySummary(
            DataSensitivity maxDataSensitivity,
            RiskLevel maxRiskLevel,
            long financeHrToolCount,
            long confidentialToolCount) {
    }
}
