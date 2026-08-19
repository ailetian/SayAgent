package com.sayagent.user.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求「收件箱」（§3.5 API 响应契约）。
 *
 * <p>大白话：前端把用户名和密码装进这个盒子 POST 给 {@code /api/auth/login}。
 * 用 record 当 DTO（不可变、字段即构造参数），配合 {@code @NotBlank} 让 Spring 在进站时
 * 就把空用户名/空密码拦下来，省得业务层再判一遍。
 *
 * <p>注：record 的字段是「天然可读」的（有 {@code username()} 这种访问器），Jackson 直接按
 * 构造参数名做序列化/反序列化，无需额外 {@code @Getter}。
 *
 * @param username 用户名（不可空）
 * @param password 明文密码（不可空；Controller 收到后再用 BCrypt 比对密文，明文不过 persist 层）
 */
public record LoginRequest(
        @NotBlank(message = "用户名不能为空") String username,
        @NotBlank(message = "密码不能为空") String password
) {
}
