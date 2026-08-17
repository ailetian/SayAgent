package com.hify.hify.rbac;

import com.hify.hify.rbac.dto.MeResponse;

/**
 * 当前登录人「身份快照」服务（M9/T4）。
 *
 * <p>大白话：登录后前端要渲染侧边栏、做路由守卫，都来问这一个接口——
 * 它返回「我是谁（role/roles）+ 我能看哪些菜单（menus）」。
 *
 * <p>rbac 包自包含：本服务只依赖自身仓储 + T2 的统一身份工具 {@code AuthContext}，
 * 不反向依赖任何业务包（§3.2 跨模块纪律）。
 */
public interface MeService {

    /** 取当前登录人的身份快照（角色 + 可见菜单）。 */
    MeResponse me();
}
