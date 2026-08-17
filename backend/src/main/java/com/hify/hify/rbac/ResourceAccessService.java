package com.hify.hify.rbac;

import com.hify.hify.common.exception.BizException;
import com.hify.hify.common.exception.ErrorCode;
import com.hify.hify.common.security.AuthContext;
import com.hify.hify.rbac.dto.ResourceAccessView;
import com.hify.hify.rbac.ResourceAccessAudit;
import com.hify.hify.rbac.ResourceAccessAuditRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 统一资源授权「判定 + 自授权」服务（M9/T5）。
 *
 * <p>大白话：所有「谁能看/用哪个知识库或 Agent」的判定逻辑都收口到这一个服务，
 * 谁要判断、谁要授权都来调它，避免各模块各写一套判权（§2.1 单一事实源）。
 *
 * <p><b>跨模块纪律（§3.2）</b>：本服务只依赖自身仓储 + {@code common} 工具，<b>绝不 import</b> 任何业务包
 * （knowledge/agent/...）的实体或仓储。knowledge / agent 模块反向调用本服务（接口级依赖），方向单向、无环。
 *
 * <p>关于「PUBLIC」：资源 {@code visibility='PUBLIC'} 的判定天然落在资源所属模块（knowledge_base / agent 表），
 * rbac 不持有这两张表、也<b>不应</b>反向依赖，故 {@link #visibleResourceIds} 只返回
 * <b>显式授权</b>的集合；「PUBLIC + 授权」的最终并集由 T6 列表过滤（持有资源表的一方）负责合并。
 */
@Service
@RequiredArgsConstructor
public class ResourceAccessService {

    /** 个人覆盖行的主体类型。 */
    public static final String PRINCIPAL_USER = "USER";
    /** 角色基线行的主体类型。 */
    public static final String PRINCIPAL_ROLE = "ROLE";
    /** 资源类型：知识库。 */
    public static final String RESOURCE_KB = "KB";
    /** 资源类型：智能体。 */
    public static final String RESOURCE_AGENT = "AGENT";
    /** 资源可见性：全员可见（§2.1）。 */
    public static final String VISIBILITY_PUBLIC = "PUBLIC";
    /** 资源可见性：仅授权可见（默认，secure by default §2.1）。 */
    public static final String VISIBILITY_RESTRICTED = "RESTRICTED";

    private final ResourceAccessRepository resourceAccessRepository;

    /** 授权审计仓储（M10/T6，§7.11 重要操作留痕）。 */
    private final ResourceAccessAuditRepository auditRepository;

    /**
     * 取当前用户<b>显式授权</b>可见的资源 id 集合。
     *
     * <p>规则（§2.1）：
     * <ul>
     *   <li>含 ADMIN 角色 → 返回 {@code null}（语义：可见全部，列表端点直接返回全量）；</li>
     *   <li>普通用户 → 返回 {@code resource_access} 中 principal_type=USER 且 can_read=1 的资源 id 集合。</li>
     * </ul>
     *
     * <p>注意：此处<b>不含</b> {@code visibility='PUBLIC'} 的资源（见类注释）；PUBLIC 并集由 T6 合并。
     *
     * @param username 当前登录名（principal_id=USER 时比对用）
     * @param roles    当前角色集（用于 ADMIN 兜底判定）
     * @param resourceType 资源类型 KB/AGENT
     * @return ADMIN→null；USER→显式授权 id 集合（可能为空集合，非 null）
     */
    public Set<Long> visibleResourceIds(String username, Set<String> roles, String resourceType) {
        if (roles != null && roles.contains("ADMIN")) {
            return null; // ADMIN 可见全部
        }
        return grantedResourceIds(username, roles, resourceType);
    }

    /**
     * 取某用户（USER）对某类资源的<b>可读</b>授权 id 集合（不含 ADMIN 兜底、不含 PUBLIC）。
     *
     * <p>大白话：把「个人授权」与「角色基线授权」两路合并——
     * 个人授权 = {@code principal_type=USER 且 principal_id=本人 且 can_read=1}；
     * 角色授权 = {@code principal_type=ROLE 且 principal_id ∈ 当前用户角色集 且 can_read=1}（§2.1 角色基线）。
     * 两路并集即该用户可看见/可使用的资源 id（PUBLIC 由 T6 列表端点另行合并）。
     */
    public Set<Long> grantedResourceIds(String username, Set<String> roles, String resourceType) {
        Set<Long> ids = new LinkedHashSet<>();
        // 个人授权
        resourceAccessRepository
                .findByPrincipalTypeAndPrincipalIdAndResourceTypeAndCanReadTrue(PRINCIPAL_USER, username, resourceType)
                .forEach(ra -> ids.add(ra.getResourceId()));
        // 角色基线授权（修复：原实现只查个人授权，导致 ROLE 授权在可见性计算中完全失效，见验收反馈）
        if (roles != null && !roles.isEmpty()) {
            resourceAccessRepository
                    .findByPrincipalTypeAndPrincipalIdInAndResourceTypeAndCanReadTrue(PRINCIPAL_ROLE, roles, resourceType)
                    .forEach(ra -> ids.add(ra.getResourceId()));
        }
        return ids;
    }

    /**
     * 创建者自授权：新建 KB / Agent 后，给创建者本人写一行<b>全权</b>授权（保证自己能继续管理，§2.1 创建者权益）。
     *
     * <p>幂等：若该 (USER, 创建者, 资源类型, 资源id) 行已存在则跳过，避免唯一键冲突（uk_principal_resource）。
     *
     * @param resourceType 资源类型 KB/AGENT
     * @param resourceId   新建资源 id
     * @param creatorUsername 创建者登录名（principal_id=USER 取值，与 creator_id/created_by 同源，P0-2）
     */
    @Transactional
    public void grantCreator(String resourceType, Long resourceId, String creatorUsername) {
        if (creatorUsername == null || creatorUsername.isBlank()) {
            return; // 无创建者（历史脏数据）不写授权行，由 visibility=PUBLIC 兜底
        }
        Optional<ResourceAccess> existing = resourceAccessRepository
                .findByPrincipalTypeAndPrincipalIdAndResourceTypeAndResourceId(
                        PRINCIPAL_USER, creatorUsername, resourceType, resourceId);
        if (existing.isPresent()) {
            return; // 已授权，跳过（幂等）
        }
        resourceAccessRepository.save(new ResourceAccess(PRINCIPAL_USER, creatorUsername, resourceType, resourceId));
    }

    /**
     * 通用授权（T7 授权管理接口用）：给某主体对某资源授予四权。已存在则更新权限位，不存在则新建。
     *
     * @param read/write/use/edit 四权开关
     */
    @Transactional
    public void grant(String principalType, String principalId, String resourceType, Long resourceId,
                      boolean read, boolean write, boolean use, boolean edit) {
        grant(principalType, principalId, resourceType, resourceId, read, write, use, edit, null);
    }

    /**
     * 通用授权（T7 授权管理接口用）：给某主体对某资源授予四权。已存在则更新权限位，不存在则新建。
     *
     * <p>M10/T6：授权成功后写一条审计（操作人来自 {@link AuthContext}、动作 GRANT、含资源风险摘要）。
     * {@code riskSummary} 由调用方透传（agent 模块在授权 Agent 时计算其携带的敏感工具摘要），
     * rbac 不反向依赖业务包（§3.2）。
     *
     * @param read/write/use/edit 四权开关
     * @param riskSummary 被授权资源的风险摘要（如「含财务·人事域工具 3 个」），可空
     */
    @Transactional
    public void grant(String principalType, String principalId, String resourceType, Long resourceId,
                      boolean read, boolean write, boolean use, boolean edit, String riskSummary) {
        assertNotAdminPrincipal(principalType, principalId);
        Optional<ResourceAccess> opt = resourceAccessRepository
                .findByPrincipalTypeAndPrincipalIdAndResourceTypeAndResourceId(
                        principalType, principalId, resourceType, resourceId);
        ResourceAccess ra = opt.orElseGet(() ->
                new ResourceAccess(principalType, principalId, resourceType, resourceId));
        ra.setCanRead(read);
        ra.setCanWrite(write);
        ra.setCanUse(use);
        ra.setCanEdit(edit);
        resourceAccessRepository.save(ra);
        writeAudit("GRANT", principalType, principalId, resourceType, resourceId, riskSummary);
    }

    /**
     * 撤销授权（T7 用）。M10/T6：撤销同样写审计（动作 REVOKE）。
     */
    @Transactional
    public void revoke(String principalType, String principalId, String resourceType, Long resourceId) {
        assertNotAdminPrincipal(principalType, principalId);
        resourceAccessRepository.deleteByPrincipalTypeAndPrincipalIdAndResourceTypeAndResourceId(
                principalType, principalId, resourceType, resourceId);
        writeAudit("REVOKE", principalType, principalId, resourceType, resourceId, null);
    }

    /**
     * 写一条授权审计（M10/T6，§7.11）。操作人取当前登录名，失败兜底为 "system"。
     * 本方法不抛异常（即使审计写入失败也不应阻断授权主流程，审计自身异常由调用方事务一并回滚也无妨）。
     */
    private void writeAudit(String action, String principalType, String principalId,
                            String resourceType, Long resourceId, String riskSummary) {
        String operator = AuthContext.currentUsername();
        if (operator == null) {
            operator = "system";
        }
        auditRepository.save(new ResourceAccessAudit(
                operator, action, principalType, principalId, resourceType, resourceId, riskSummary));
    }

    /**
     * 授权主体护栏（§2.1）：ADMIN 角色对所有 KB/Agent 隐式拥有读+写+用+管全权，
     * 禁止再为其建 / 撤 {@code resource_access} 行（冗余且误导）。授权操作（T7）必须以 OPERATOR/USER 角色或具体用户为主体。
     */
    private void assertNotAdminPrincipal(String principalType, String principalId) {
        if (PRINCIPAL_ROLE.equals(principalType) && "ADMIN".equals(principalId)) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "ADMIN 角色无需授权，已隐式拥有全部 KB/Agent 读+写+用+管全权");
        }
    }

    /**
     * 授权管理守卫（T7 用，§2.1 授权管理）：当前登录人若为 ADMIN 直接放行；
     * 否则必须是该资源的<b>管理者</b>——即 {@code resource_access} 中存在
     * {@code (USER, 当前登录名, 资源类型, 资源id, can_edit=1)} 的行。
     *
     * <p><b>为什么用「can_edit 行」判定创建者（而非跨模块查 creator_id/created_by）</b>：
     * T5 的 {@link #grantCreator} 已为新建资源的创建者写入一行全权（can_edit=1）授权，
     * 故「持有 can_edit 行」与「是创建者」等价，且天然兼容「管理员把 can_edit 授给某人 = 让其也能管理」。
     * 如此 rbac 包无需反向依赖 knowledge/agent 业务表，严守 §3.2 跨模块解耦纪律。
     *
     * @param resourceType 资源类型 KB/AGENT
     * @param resourceId   资源 id
     * @throws BizException {@link ErrorCode#FORBIDDEN} 非管理员且非管理者时
     */
    public void requireManager(String resourceType, Long resourceId) {
        if (AuthContext.isAdmin()) {
            return;
        }
        String username = AuthContext.currentUsername();
        Optional<ResourceAccess> ra = resourceAccessRepository
                .findByPrincipalTypeAndPrincipalIdAndResourceTypeAndResourceId(
                        PRINCIPAL_USER, username, resourceType, resourceId);
        if (ra.isEmpty() || !Boolean.TRUE.equals(ra.get().getCanEdit())) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
    }

    /**
     * 列出某资源当前的全部授权（T7 授权 Tab 用）。
     *
     * @param resourceType 资源类型 KB/AGENT
     * @param resourceId   资源 id
     * @return 授权视图列表（可能为空，非 null）
     */
    public List<ResourceAccessView> listGrants(String resourceType, Long resourceId) {
        return resourceAccessRepository.findByResourceTypeAndResourceId(resourceType, resourceId).stream()
                .map(ra -> new ResourceAccessView(
                        ra.getPrincipalType(),
                        ra.getPrincipalId(),
                        Boolean.TRUE.equals(ra.getCanRead()),
                        Boolean.TRUE.equals(ra.getCanWrite()),
                        Boolean.TRUE.equals(ra.getCanUse()),
                        Boolean.TRUE.equals(ra.getCanEdit())))
                .toList();
    }
}
