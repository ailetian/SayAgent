package com.hify.hify.user;

import com.hify.hify.common.Result;
import com.hify.hify.user.dto.CreateUserRequest;
import com.hify.hify.user.dto.UserVO;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户管理接口「前台柜员」（M9/T3，§3.4 分层纪律：Controller 极薄——只收请求、调 service、装统一盒子）。
 *
 * <p>大白话：这是 {@code /api/users} 的入口，提供两件管理员后台能力：
 * <ul>
 *   <li>{@code POST /api/users}：新建用户（选角色）；</li>
 *   <li>{@code GET /api/users}：列出全部用户（脱敏，无密码）。</li>
 * </ul>
 *
 * <p>门禁：该路径在 {@code SecurityConfig} 走 {@code anyRequest().authenticated()}，须带有效 token；
 * 未登录 → AuthFilter 拦截返 401；已登录但非 ADMIN → 服务层 {@code requireAdmin} 返 403（§7.11）。
 * Controller 自身只做参数校验（{@code @Valid}）与响应封装，不写任何鉴权/业务判断。
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping
    public Result<UserVO> createUser(@Valid @RequestBody CreateUserRequest request) {
        return Result.ok(userService.createUser(request));
    }

    @GetMapping
    public Result<List<UserVO>> listUsers() {
        return Result.ok(userService.listUsers());
    }
}
