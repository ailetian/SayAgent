package com.hify.hify.rbac;

import com.hify.hify.common.base.BaseRepository;
import java.util.List;
import java.util.Set;

/**
 * 角色-菜单映射数据访问（M9/T4）。按角色编码集合查该角色可见的菜单编码。
 * 软删由 {@link RoleMenu} 上的 {@code @SQLRestriction} 自动过滤（§6.1）。
 */
public interface RoleMenuRepository extends BaseRepository<RoleMenu> {

    /** 按角色编码集合查映射行（如 ADMIN → 7 行）。 */
    List<RoleMenu> findByRoleCodeIn(Set<String> roleCodes);
}
