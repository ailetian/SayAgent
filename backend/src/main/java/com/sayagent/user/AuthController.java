package com.sayagent.user;

import com.sayagent.common.Result;
import com.sayagent.user.dto.LoginRequest;
import com.sayagent.user.dto.LoginResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录接口「前台柜员」（§3.4 分层纪律：Controller 极薄——只收请求、调 service、装统一盒子）。
 *
 * <p>大白话：这是 {@code /api/auth/login} 这个 POST 入口。它自己不写任何业务逻辑：
 * <ul>
 *   <li>{@code @Valid} 先把空用户名/空密码挡在门外；</li>
 *   <li>把请求交给 {@link AuthService#login}；</li>
 *   <li>成功就用 {@link Result#ok} 包成统一响应；失败由 {@code GlobalExceptionHandler} 自动翻译。</li>
 * </ul>
 *
 * <p>门禁：该路径在 {@code SecurityConfig} 里被 {@code permitAll()}，无需带 token 也能访问
 * （登录本身就是为了拿 token）。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return Result.ok(authService.login(request));
    }
}
