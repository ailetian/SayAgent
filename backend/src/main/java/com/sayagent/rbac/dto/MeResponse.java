package com.sayagent.rbac.dto;

import java.util.List;
import java.util.Set;

/**
 * 「我是谁 + 我能看什么」响应（M9/T4 + T5，§3.5 统一响应契约的数据体）。
 *
 * <p>字段对应契约 {@code {role, roles:[...], menus:[{code,title,route,icon}], accessibleKbIds, accessibleAgentIds}}：
 * <ul>
 *   <li>{@code role}：主角色（单角色系统取首个）；</li>
 *   <li>{@code roles}：角色数组（如 ["ADMIN"]）；</li>
 *   <li>{@code menus}：可见菜单视图列表，title 取自 menu_item.name，route 取自 menu_item.path；</li>
 *   <li>{@code accessibleKbIds}：当前用户<b>显式授权</b>可见的知识库 id 集合（T5）；ADMIN 为 {@code null}（列表端点返回全量）；</li>
 *   <li>{@code accessibleAgentIds}：同上，对 Agent。PUBLIC 资源对所有人可见，无需列入此集合。</li>
 * </ul>
 *
 * @param role                主角色名（ADMIN/OPERATOR/USER）
 * @param roles               角色数组
 * @param menus               可见菜单视图
 * @param accessibleKbIds     显式授权的知识库 id 集合（ADMIN=null）
 * @param accessibleAgentIds  显式授权的 Agent id 集合（ADMIN=null）
 */
public record MeResponse(String role, List<String> roles, List<MenuVO> menus,
                        Set<Long> accessibleKbIds, Set<Long> accessibleAgentIds) {

    /**
     * 菜单视图（侧边栏渲染用）。
     *
     * @param code  菜单编码（前端路由 meta 映射键）
     * @param title 显示名（menu_item.name）
     * @param route 前端路由（menu_item.path）
     * @param icon  图标名（menu_item.icon）
     */
    public record MenuVO(String code, String title, String route, String icon) {
    }
}
