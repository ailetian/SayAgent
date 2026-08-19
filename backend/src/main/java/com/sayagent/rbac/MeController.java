package com.sayagent.rbac;

import com.sayagent.common.Result;
import com.sayagent.rbac.dto.MeResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前登录人接口「前台柜员」（M9/T4，§3.4 分层纪律：极薄——只收请求、调服务、装统一盒子）。
 *
 * <p>大白话：这是 {@code GET /api/me} 入口。登录即可访问（无需 admin，§7.11）；
 * 未登录由 {@code AuthFilter} 拦截返 401。返回 {@code {role, roles, menus}}（§3.5 响应契约），
 * 前端据此动态渲染侧边栏 + 做路由守卫。
 */
@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class MeController {

    private final MeService meService;

    @GetMapping
    public Result<MeResponse> me() {
        return Result.ok(meService.me());
    }
}
