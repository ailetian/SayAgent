package com.hify.hify.rbac;

import com.hify.hify.common.security.AuthContext;
import com.hify.hify.rbac.dto.MeResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 当前登录人身份快照「实现」（M9/T4 + T5，§3.2 分层纪律：Controller 只调本服务，本服务才碰仓储）。
 *
 * <p>查询逻辑：拿当前用户角色集 → 查 {@code role_menu} 得到可见菜单编码 →
 * 查 {@code menu_item} 拼成菜单视图（code/title/route/icon）；同时向 rbac 授权服务取
 * 当前用户<b>显式授权</b>可见的 KB / Agent id 集合（T5）。
 * 角色来源统一走 {@link AuthContext#roleSet()}（与 {@code KbAccessGuard} 判定一致，T2 落地）。
 */
@Service
@RequiredArgsConstructor
public class MeServiceImpl implements MeService {

    private final RoleMenuRepository roleMenuRepository;
    private final MenuItemRepository menuItemRepository;
    /** T5：资源授权服务（rbac 自包含，本服务仅依赖它，不碰业务表）。 */
    private final ResourceAccessService resourceAccessService;

    @Override
    public MeResponse me() {
        Set<String> roles = AuthContext.roleSet();

        // 角色 → 可见菜单编码（去重保序）
        Set<String> menuCodes = new LinkedHashSet<>();
        if (!roles.isEmpty()) {
            menuCodes = roleMenuRepository.findByRoleCodeIn(roles).stream()
                    .map(RoleMenu::getMenuCode)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        // 菜单编码 → 菜单视图（按 sort 升序）
        List<MeResponse.MenuVO> menus = menuCodes.isEmpty()
                ? List.of()
                : menuItemRepository.findByCodeInOrderBySortAsc(menuCodes).stream()
                        .map(m -> new MeResponse.MenuVO(m.getCode(), m.getName(), m.getPath(), m.getIcon()))
                        .collect(Collectors.toList());

        // 单角色系统下取首个角色作为主 role；多角色时取首个（保序）
        String role = roles.isEmpty() ? null : roles.iterator().next();

        // T5：资源可见 id 集合
        Set<Long> accessibleKbIds;
        Set<Long> accessibleAgentIds;
        if (roles.isEmpty()) {
            // 未登录：菜单已空，资源 id 置空集合（不抛异常，与 T4 未登录行为一致）
            accessibleKbIds = Set.of();
            accessibleAgentIds = Set.of();
        } else if (roles.contains("ADMIN")) {
            // ADMIN：可见全部，列表端点直接返回全量，此处置 null（语义：全部）
            accessibleKbIds = null;
            accessibleAgentIds = null;
        } else {
            String username = AuthContext.currentUsername();
            accessibleKbIds = resourceAccessService.visibleResourceIds(username, roles, ResourceAccessService.RESOURCE_KB);
            accessibleAgentIds = resourceAccessService.visibleResourceIds(username, roles, ResourceAccessService.RESOURCE_AGENT);
        }

        return new MeResponse(role, new ArrayList<>(roles), menus, accessibleKbIds, accessibleAgentIds);
    }
}
