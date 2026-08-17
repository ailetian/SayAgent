package com.hify.hify.user.dto;

import com.hify.hify.user.UserRole;

import jakarta.validation.constraints.NotBlank;

/**
 * 新建用户请求「收件箱」（M9/T3，§3.5 API 响应契约）。
 *
 * <p>大白话：管理员在后台填的建用户表单，装进这个盒子 POST 给 {@code /api/users}。
 * 用 record 当 DTO（不可变、字段即构造参数），配合 {@code @NotBlank} 让 Spring 在进站时
 * 就把空用户名/空密码拦下来，省得业务层再判一遍。
 *
 * <p>注意：<b>不在此处对 password 设最小长度</b>——服务层 {@code requireAdmin} 守卫须先于长度校验
 * 命中（非 ADMIN 调建用户应返回 403 FORBIDDEN，而非 400 参数校验失败）。
 *
 * @param username    用户名（不可空）
 * @param password    明文密码（不可空；Controller 收到后再用 BCrypt 加密，明文不过 persist 层，§7.11）
 * @param role        角色（ADMIN/OPERATOR/USER，可空，缺省 {@code UserRole.USER}，§7.2 禁魔法数字）
 * @param displayName 显示名（可空，V31 补列）
 * @param email       邮箱（可空，V31 补列）
 */
public record CreateUserRequest(
        @NotBlank(message = "用户名不能为空") String username,
        @NotBlank(message = "密码不能为空") String password,
        UserRole role,
        String displayName,
        String email
) {
}
