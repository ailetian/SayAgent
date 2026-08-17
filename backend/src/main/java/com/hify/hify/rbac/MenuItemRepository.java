package com.hify.hify.rbac;

import com.hify.hify.common.base.BaseRepository;
import java.util.List;
import java.util.Set;

/**
 * 菜单定义数据访问（M9/T4）。软删由 {@link MenuItem} 上的 {@code @SQLRestriction} 自动过滤（§6.1）。
 */
public interface MenuItemRepository extends BaseRepository<MenuItem> {

    /** 按菜单编码集合查菜单，按 sort 升序（动态侧边栏渲染顺序）。 */
    List<MenuItem> findByCodeInOrderBySortAsc(Set<String> codes);
}
